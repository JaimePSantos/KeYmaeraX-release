package edu.cmu.cs.ls.keymaerax.btactics

import edu.cmu.cs.ls.keymaerax.bellerophon._
import edu.cmu.cs.ls.keymaerax.infrastruct._
import edu.cmu.cs.ls.keymaerax.btactics.TactixLibrary._
import edu.cmu.cs.ls.keymaerax.btactics.Idioms._
import edu.cmu.cs.ls.keymaerax.btactics.SimplifierV3._
import edu.cmu.cs.ls.keymaerax.btactics.TacticFactory._
import edu.cmu.cs.ls.keymaerax.btactics.AnonymousLemmas._
import edu.cmu.cs.ls.keymaerax.core.{NamedSymbol, _}
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import edu.cmu.cs.ls.keymaerax.infrastruct.Augmentors._
import edu.cmu.cs.ls.keymaerax.{Configuration, Logging}
import edu.cmu.cs.ls.keymaerax.btactics.Generator.Generator
import edu.cmu.cs.ls.keymaerax.btactics.InvariantGenerator.{AnnotationProofHint, GenProduct, PegasusProofHint}
import edu.cmu.cs.ls.keymaerax.btactics.helpers.DifferentialHelper
import edu.cmu.cs.ls.keymaerax.infrastruct._
import edu.cmu.cs.ls.keymaerax.pt.ProvableSig
import edu.cmu.cs.ls.keymaerax.tools._
import edu.cmu.cs.ls.keymaerax.btactics.macros.DerivationInfoAugmentors._
import edu.cmu.cs.ls.keymaerax.btactics.macros.{AxiomInfo, Tactic}
import edu.cmu.cs.ls.keymaerax.infrastruct.ExpressionTraversal.ExpressionTraversalFunction
import edu.cmu.cs.ls.keymaerax.tools.qe.BigDecimalQETool

import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.immutable.{IndexedSeq, List, Nil, Seq}
import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
 * Implementation: provides tactics for differential equations.
  *
  * @note Container for "complicated" tactics. Single-line implementations are in [[TactixLibrary]].
 * @see [[TactixLibrary.DW]], [[TactixLibrary.DC]]
 */
private object DifferentialTactics extends Logging {

  private val namespace = "differentialtactics"

  // QE with default timeout for use in ODE tactics (timeout in seconds)
  private[btactics] val ODE_QE_TIMEOUT = Integer.parseInt(Configuration(Configuration.Keys.ODE_TIMEOUT_FINALQE))
  private[btactics] def timeoutQE = ToolTactics.hideNonFOL & QE(Nil, None, Some(ODE_QE_TIMEOUT))
  // QE with default timeout for use in counterexample tactics (timeout in seconds)
  private[btactics] val ODE_CEX_TIMEOUT =
      Try(Integer.parseInt(Configuration(Configuration.Keys.Pegasus.INVCHECK_TIMEOUT))).getOrElse(-1)
  private[btactics] def timeoutCEXQE = QE(Nil, None, Some(ODE_CEX_TIMEOUT))

  /** @see [[HilbertCalculus.DE]] */
  lazy val DE: DependentPositionTactic = new DependentPositionTactic("DE") {
    //@todo investigate why unification fails and causes unnecessarily complicated tactic. And get rid of duplicate implementation
    override def factory(pos: Position): DependentTactic = new SingleGoalDependentTactic(name) {
      override def computeExpr(sequent: Sequent): BelleExpr = if (RenUSubst.semanticRenaming) {
        if (isODESystem(sequent, pos)) {
          DESystemStep_SemRen(pos)*getODEDim(sequent, pos)
          //@todo unification fails
          // TactixLibrary.useAt(Ax.DEs)(pos)*getODEDim(provable.subgoals.head, pos)
        } else {
          useAt(Ax.DE)(pos)
        }
      } else {
        import ProofRuleTactics.contextualize
        if (isODESystem(sequent, pos)) {
          if (HilbertCalculus.INTERNAL) TactixLibrary.useAt(Ax.DEs)(pos)*getODEDim(sequent, pos)
          else contextualize(DESystemStep_NoSemRen, predictor)(pos)*getODEDim(sequent, pos)
          //@todo unification fails
          // TactixLibrary.useAt(DerivedAxioms.DEsys)(pos)*getODEDim(provable.subgoals.head, pos)
        } else {
          if (HilbertCalculus.INTERNAL) useAt(Ax.DE)(pos)
          else contextualize(DESystemStep_NoSemRen, predictor)(pos)
        }
      }

      private def predictor(fml: Formula): Formula = fml match {
        case Box(ODESystem(DifferentialProduct(AtomicODE(xp@DifferentialSymbol(x), t), c), h), p) =>
          Box(
            ODESystem(DifferentialProduct(c, AtomicODE(xp, t)), h),
            Box(Assign(xp, t), p)
          )

        case Box(ODESystem(AtomicODE(xp@DifferentialSymbol(x), t), h), p) =>
          Box(
            ODESystem(AtomicODE(xp, t), h),
            Box(Assign(xp, t), p)
          )
        case _ => logger.error("Unsure how to predict DE outcome for " + fml); ???
      }
    }

    /** A single step of DE system (@todo replace with useAt when unification for this example works) */
    // semanticRenaming
    // was "DE system step"
    private lazy val DESystemStep_SemRen: DependentPositionTactic = anon ((pos: Position, sequent: Sequent) => sequent.sub(pos) match {
      case Some(f@Box(ODESystem(DifferentialProduct(AtomicODE(d@DifferentialSymbol(x), t), c), h), p)) =>
        val g = Box(
          ODESystem(DifferentialProduct(c, AtomicODE(d, t)), h),
          Box(Assign(d, t), p)
        )

        //construct substitution
        val aF = UnitFunctional("f", AnyArg, Real)
        val aH = UnitPredicational("H", AnyArg)
        val aC = DifferentialProgramConst("c", AnyArg)
        val aP = UnitPredicational("p", AnyArg)
        val aX = Variable("x_")

        val subst = USubst(SubstitutionPair(aF, t) :: SubstitutionPair(aC, c) :: SubstitutionPair(aP, p) ::
          SubstitutionPair(aH, h) :: Nil)
        val uren = ProofRuleTactics.uniformRename(aX, x)
        val origin = Sequent(IndexedSeq(), IndexedSeq(s"[{${d.prettyString}=f(||),c&H(||)}]p(||) <-> [{c,${d.prettyString}=f(||)&H(||)}][${d.prettyString}:=f(||);]p(||)".asFormula))

        cutLR(g)(pos) <(
          /* use */ skip,
          //@todo conditional commuting (if (pos.isSucc) commuteEquivR(1) else Idioms.ident) instead?
          /* show */ cohide('Rlast) & equivifyR(1) & commuteEquivR(1) &
          TactixLibrary.US(subst, TactixLibrary.uniformRenameF(aX, x)(AxiomInfo("DE differential effect (system)").provable)))
          //TactixLibrary.US(subst, "DE differential effect (system)"))
      case Some(e) => throw new TacticInapplicableFailure("DE system step only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
    })

    /** A single step of DE system */
    // !semanticRenaming
    // was "DE system step"
    private lazy val DESystemStep_NoSemRen: DependentPositionTactic = anon ((pos: Position, sequent: Sequent) => sequent.sub(pos) match {
      case Some(f@Box(ODESystem(DifferentialProduct(AtomicODE(xp@DifferentialSymbol(x), t), c), h), p)) =>
        useAt(Ax.DEs)(pos)
      case Some(f@Box(ODESystem(AtomicODE(xp@DifferentialSymbol(x), t), h), p)) =>
        useAt(Ax.DE)(pos)
      case Some(e) => throw new TacticInapplicableFailure("DE system step only applicable to formulas, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
    })
  }

  /** @see [[TactixLibrary.dI]] */
  def diffInd(auto: Symbol = 'full): DependentPositionTactic = {
    if (!(auto == 'full || auto == 'none || auto == 'diffInd || auto == 'cex)) throw new TacticRequirementError("Expected one of ['none, 'diffInd, 'full, 'cex] automation values, but got " + auto)
    anon { (pos: Position, sequent: Sequent) => {
      if (!pos.isSucc) throw new IllFormedTacticApplicationException("diffInd only applicable to succedent positions, but got " + pos.prettyString)
      val diFml: Formula = sequent.sub(pos) match {
        case Some(b@Box(_: ODESystem, p)) =>
          if (p.isFOL) b
          else throw new TacticInapplicableFailure("diffInd only applicable to FOL postconditions, but got " + p.prettyString)
        case Some(e) => throw new TacticInapplicableFailure("diffInd only applicable to box ODEs in succedent, but got " + e.prettyString)
        case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
      }

      val expand: BelleExpr = diFml match {
        case Box(_, post) if StaticSemantics.symbols(post).exists(
          { case Function(_, _, _, _, interpreted) => interpreted case _ => false }) =>
          // expand all interpreted function symbols below pos.1
          EqualityTactics.expandAllAt(pos ++ PosInExpr(1::Nil))
        case _ => skip
      }

      val abbrvPrimes = anon { (pos: Position, seq: Sequent) => seq.sub(pos) match {
        case Some(e: Expression) =>
          val vprimes = scala.collection.mutable.Set.empty[DifferentialSymbol]
          ExpressionTraversal.traverseExpr(new ExpressionTraversalFunction() {
            override def preT(p: PosInExpr, t: Term): Either[Option[ExpressionTraversal.StopTraversal], Term] = t match {
              case x: DifferentialSymbol => vprimes += x; Left(None)
              case _ => Left(None)
            }
          }, e)
          vprimes.map(EqualityTactics.abbrv(_, None) & hideL('Llast)).reduceRightOption[BelleExpr](_ & _).getOrElse(skip)
        case _ => skip
      }}

      if (pos.isTopLevel) {
        val t = expand & DI(pos) &
          //@note implyR moves RHS to end of succedent
          implyR(pos) & andR('Rlast) & Idioms.<(
            if (auto == 'full) ToolTactics.hideNonFOL & (abbrvPrimes('Rlast) & QE & done | DebuggingTactics.done("Differential invariant must hold in the beginning"))
            else if (auto == 'cex) ToolTactics.hideNonFOL & ?(abbrvPrimes('Rlast) & QE) & label(BelleLabels.dIInit)
            else skip
            ,
            if (auto != 'none) {
              //@note derive before DE to keep positions easier
              derive('Rlast, PosInExpr(1 :: Nil)) &
              DE('Rlast) &
              (if (auto == 'full || auto == 'cex)
                TryCatch(Dassignb('Rlast, PosInExpr(1::Nil))*getODEDim(sequent, pos), classOf[SubstitutionClashException],
                  (_: SubstitutionClashException) =>
                    DebuggingTactics.error("After deriving, the right-hand sides of ODEs cannot be substituted into the postcondition")
                ) &
                //@note DW after DE to keep positions easier
                (if (hasODEDomain(sequent, pos)) DW('Rlast) else skip) & abstractionb('Rlast) & ToolTactics.hideNonFOL &
                  (if (auto == 'full) abbrvPrimes('Rlast) & QE & done | DebuggingTactics.done("Differential invariant must be preserved")
                   else ?(abbrvPrimes('Rlast) & QE) & label(BelleLabels.dIStep))
               else {
                assert(auto == 'diffInd)
                (if (hasODEDomain(sequent, pos)) DW('Rlast) else skip) &
                abstractionb('Rlast) & SaturateTactic(allR('Rlast)) & ?(implyR('Rlast)) })
            } else skip
            )
        Dconstify(t)(pos)
      } else {
        val t = expand & DI(pos) &
          (if (auto != 'none) {
            shift(PosInExpr(1 :: 1 :: Nil), anon ((pos: Position, sequent: Sequent) =>
              //@note derive before DE to keep positions easier
              shift(PosInExpr(1 :: Nil), derive)(pos) &
                DE(pos) &
                (if (auto == 'full || auto == 'cex) shift(PosInExpr(1 :: Nil), Dassignb)(pos)*getODEDim(sequent, pos) &
                  //@note DW after DE to keep positions easier
                  (if (hasODEDomain(sequent, pos)) DW(pos) else skip) &
                  abstractionb(pos)
                else abstractionb(pos))
              )
            )(pos)
          } else ident)
        Dconstify(t)(pos)
      }
    }
  }}

  val dI: DependentPositionTactic = anon ((pos:Position) => diffInd('cex)(pos))

  /**
   * diffInd: Differential Invariant proves a formula to be an invariant of a differential equation (by DI, DW, DE, QE)
    *
    * @example {{{
   *    x>=5 |- x>=5    x>=5 |- [{x'=2}](x>=5)'
   *    ---------------------------------------DIRule(qeTool)(1)
   *    x>=5 |- [{x'=2}]x>=5
   * }}}
   * @example {{{
   *    x>=5 |- [x:=x+1;](true->x>=5&[{x'=2}](x>=5)')
   *    ---------------------------------------------DIRule(qeTool)(1, 1::Nil)
   *    x>=5 |- [x:=x+1;][{x'=2}]x>=5
   * }}}
   * @incontext
   */
  lazy val DIRule: DependentPositionTactic = diffInd('none)
  lazy val diffIndRule: DependentPositionTactic = diffInd('diffInd)

  /** [[DifferentialEquationCalculus.openDiffInd]] */
  private[btactics] lazy val openDiffInd: DependentPositionTactic = new DependentPositionTactic("openDiffInd") {
    override def factory(pos: Position): DependentTactic = new SingleGoalDependentTactic(name) {
      override def computeExpr(sequent: Sequent): BelleExpr = {
        require(pos.isSucc && pos.isTopLevel, "openDiffInd only at ODE system in succedent")
        val (axUse:AxiomInfo,der) = sequent.sub(pos) match {
          case Some(Box(_: ODESystem, _: Greater)) => (Ax.DIogreater,true)
          case Some(Box(_: ODESystem, _: Less)) => (Ax.DIoless,true)
          case Some(e) => throw new TacticInapplicableFailure("openDiffInd only at ODE system in succedent with an inequality in the postcondition (f>g,f<g), but got " + e.prettyString)
          case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
        }
        if (pos.isTopLevel) {
          val t = useAt(axUse)(pos) <(
              testb(pos) & ToolTactics.hideNonFOL & QE & done,
              //@note derive before DE to keep positions easier
              implyR(pos) & (
                if(der) derive(pos ++ PosInExpr(1::1::Nil))
                else derive(pos ++ PosInExpr(1::1::0::Nil)) & derive(pos ++ PosInExpr(1::1::1::Nil))) &

                DE(pos) &
                (Dassignb(pos ++ PosInExpr(1::Nil))*getODEDim(sequent, pos) &
                  //@note DW after DE to keep positions easier
                  (if (hasODEDomain(sequent, pos)) DW(pos) else skip) & abstractionb(pos) & ToolTactics.hideNonFOL & QE & done
                  )
              )
          Dconstify(t)(pos)
        } else {
          //@todo positional tactics need to be adapted
          val t = useAt(axUse)(pos) &
              shift(PosInExpr(1 :: 1 :: Nil), new DependentPositionTactic("Shift") {
                override def factory(pos: Position): DependentTactic = new SingleGoalDependentTactic(name) {
                  override def computeExpr(sequent: Sequent): BelleExpr = {
                    //@note derive before DE to keep positions easier
                    //todo: needs fixing
                    (if(der) shift(PosInExpr(1 :: Nil), derive)(pos) else ident) &
                      DE(pos) &
                      shift(PosInExpr(1 :: Nil), Dassignb)(pos)*getODEDim(sequent, pos) &
                      //@note DW after DE to keep positions easier
                      (if (hasODEDomain(sequent, pos)) DW(pos) else skip) &
                      abstractionb(pos)
                  }
                }
              }
              )(pos)
          Dconstify(t)(pos)
        }
      }
    }
  }

  /** @see [[TactixLibrary.diffVar]]
    * TODO: deprecate this
    * */
  @deprecated
  val diffVar: DependentPositionTactic = anon {(pos: Position, sequent: Sequent) =>  {
      //require(pos.isSucc, "diffVar only at ODE system in succedent")
      val greater = sequent.sub(pos) match {
        case Some(Diamond(ODESystem(_,True), _: GreaterEqual)) => true
        case Some(Diamond(ODESystem(_,True), _: LessEqual)) => false
        case Some(e) => throw new TacticInapplicableFailure("diffVar currently only implemented at ODE system with postcondition f>=g or f<=g and domain true, but got " + e.prettyString)
        case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
      }
      //@todo these axioms don't exist?
      val t = (if (greater)
        ??? //useAt("DV differential variant >=")
      else
        ??? /* useAt("DV differential variant <="))(pos) & (
        // \exists e_ (e_>0 & [{c&true}](f(||)<=g(||) -> f(||)'>=g(||)'+e_))
        derive(pos ++ PosInExpr(0::1::1::1::0::Nil)) &
          derive(pos ++ PosInExpr(0::1::1::1::1::0::Nil)) &
          DE(pos ++ PosInExpr(0::1::Nil)) &
          (Dassignb(pos ++ PosInExpr(0::1::1::Nil))*getODEDim(sequent, pos) &
            abstractionb(pos ++ PosInExpr(0::1::Nil)) & QE & done
            )*/
        )
        t
      }
    }

  /** @see [[TactixLibrary.dCC()]] */
  val dCC: DependentPositionTactic = anon { (pos: Position, seq: Sequent) =>
    useAt(Ax.DCC, PosInExpr(1::Nil))(pos) & andR(pos) & Idioms.<(skip, dWPlus(pos) & implyR('Rlast))
  }
  /** @see [[TactixLibrary.dC()]] */
  //@todo performance faster implementation for very common single invariant Formula, e.g. DifferentialEquationCalculus.dC(Formula)
  private[btactics] def diffCut(formula: Formula): DependentPositionWithAppliedInputTactic = diffCut(List(formula))
  private[btactics] def diffCut(formulas: List[Formula]): DependentPositionWithAppliedInputTactic = inputanon {(pos: Position, sequent: Sequent) => {
      formulas.map(ghostDC(_, pos, sequent)(pos)).foldRight[BelleExpr](skip)((cut, all) => cut &
        Idioms.doIf(_.subgoals.size == 2)(<(all, skip)))
    }}

  /** Looks for special 'old' function symbol in f and creates DC (possibly with ghost) */
  private def ghostDC(f: Formula, origPos: Position, origSeq: Sequent): DependentPositionTactic = anon ((pos: Position, seq: Sequent) => {
    lazy val (ode, dc) = seq.sub(pos) match {
      case Some(Box(os: ODESystem, _)) => (os, DC _)
      case Some(Diamond(os: ODESystem, _)) => (os, DCd _)
      case Some(e) => throw new TacticInapplicableFailure("ghostDC only applicable to box/diamond properties, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }

    val ov = FormulaTools.argsOf("old", f)
    if (ov.isEmpty) {
      if (FormulaTools.conjuncts(f).toSet.subsetOf(FormulaTools.conjuncts(ode.constraint).toSet)) skip else dc(f)(pos)
    } else {
      DLBySubst.discreteGhosts(ov, origSeq,
        (ghosts: List[((Term, Variable), BelleExpr)]) => {
          val posIncrements = PosInExpr(List.fill(if (pos.isTopLevel) 0 else ghosts.size)(0::1::Nil).flatten)
          val afterGhostsPos =
            if (pos.isTopLevel) LastSucc(0, pos.inExpr ++ posIncrements)
            else Fixed(pos ++ posIncrements)
          val oldified = SubstitutionHelper.replaceFn("old", f, ghosts.map(_._1).toMap)
          if (FormulaTools.conjuncts(oldified).toSet.subsetOf(FormulaTools.conjuncts(ode.constraint).toSet)) skip
          else ghosts.map(_._2).reduce(_ & _) & dc(oldified)(afterGhostsPos)
        }
      )(pos)
    }
  })

  /** Diff Refine: Domain constraint refinement step for box/diamond ODEs on either (top-level) side of a sequent
    * Hides other succedents in the refinement subgoal by default, e.g.:
    * G|- [x'=f(x)&R]P, D     G|- [x'=f(x)&Q]R, (D hidden)
    * --- dR
    * G|- [x'=f(x)&Q]P, D
    * @param f formula to refine domain constraint
    * @param hide whether to hide D in the right premise
    * @return tactic
    */
  private def diffRefineInternal(f: Formula, hide: Boolean) (pos:Position, sequent:Sequent) = {
    require(pos.isTopLevel, "dR only at top-level succedents/antecedents")
    val (newFml,ax) = sequent.sub(pos) match {
      case Some(Diamond(sys: ODESystem, post)) => (Diamond(ODESystem(sys.ode,f),post),Ax.DRd)
      case Some(Box(sys: ODESystem, post)) => (Box(ODESystem(sys.ode,f),post),Ax.DR)
      case Some(e) => throw new TacticInapplicableFailure("dR only applicable to box/diamond ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
    }
    val cpos = if (pos.isSucc) Fixed(pos) else LastSucc(0)

    cutLR(newFml)(pos) <(skip,useAt(ax,PosInExpr(1::Nil))(cpos) & (if(hide) cohideOnlyR(cpos) else skip))
  }

  // For scala-land use
  /** [[DifferentialEquationCalculus.dR]] */
  private[btactics] def diffRefine(f: Formula, hide: Boolean=true) : DependentPositionTactic =
    anon ((pos:Position,sequent: Sequent) => {
      diffRefineInternal(f, hide)(pos,sequent)
     })

  @Tactic(names="Differential Refine",
    codeName="dR", // todo: rename the tactic directly
    premises="Γ |- [x'=f(x)&Q]R ;; Γ |- [x'=f(x)&R]P, Δ",
    conclusion="Γ |- [x'=f(x)&Q]P, Δ",
    displayLevel="browse")
  def diffRefine(R:Formula) : DependentPositionWithAppliedInputTactic = inputanon ((pos : Position, sequent: Sequent) =>
    diffRefineInternal(R, true)(pos, sequent)
  )

  /** @see [[TactixLibrary.diffInvariant]] */
  private[btactics] def diffInvariant(R: List[Formula]): DependentPositionWithAppliedInputTactic =
    inputanon {(pos: Position, sequent: Sequent) =>
      //@note assumes that first subgoal is desired result, see diffCut
      //@note UnifyUSCalculus leaves prereq open at last succedent position
      if (R.size == 1) {
        TactixLibrary.dC(R.head)(pos) <(skip, DifferentialEquationCalculus.dIX(SuccPosition.base0(sequent.succ.size - 1, pos.inExpr)) & QE & done)
      } else {
        val diffIndAllButFirst = skip +: Seq.tabulate(R.length)(_ => DifferentialEquationCalculus.dIX(SuccPosition.base0(sequent.succ.size - 1, pos.inExpr)) & QE & done)
        TactixLibrary.dC(R)(pos) <(diffIndAllButFirst: _*)
      }
    }

  /** Inverse differential cut, removes the last conjunct from the evolution domain constraint.
    * @see AxiomaticODESolver.inverseDiffCut
    * */
  @Tactic(names="Inverse Differential Cut",
    codeName="dCi", // todo: rename the tactic directly
    premises="Γ |- [x'=f(x) & Q]P ;; Γ |- R, Δ",
    conclusion="Γ |- [x'=f(x) & Q∧R]P, Δ",
    displayLevel="browse")
  val inverseDiffCut: DependentPositionTactic = anon ((pos: Position, s: Sequent) => {
    val polarity = (if (pos.isSucc) 1 else -1) * FormulaTools.polarityAt(s(pos.top), pos.inExpr)
    val fact = s.at(pos) match {
      case (ctx, fml: Modal) =>
        val (remainder, last) = fml.program match {
          case ODESystem(_, And(l, r)) => (l, r)
          case ODESystem(_, edc) => (True, edc)
        }
        val factFml =
          if (polarity > 0) Imply(last, Imply(fml.replaceAt(PosInExpr(0::1::Nil), remainder), fml))
          else Imply(last, Imply(fml, ctx(fml.replaceAt(PosInExpr(0::1::Nil), remainder))))
        proveBy(factFml,
          implyR(1)*2 & diffCut(last)(if (polarity > 0) -2 else 1) <(
            Idioms.?(useAt(Ax.trueAnd)(-2, PosInExpr(0::1::Nil))) & close
            ,
            cohideOnlyR('Rlast) & diffInd()(1) & DebuggingTactics.done
          )
        )
      case (_, e) => throw new TacticInapplicableFailure("dCi only applicable to modal box/diamond properties, but got " + e.prettyString)
    }
    useAt(fact, PosInExpr(1::(if (polarity > 0) 1 else 0)::Nil))(pos)
  })

  /**
    * Turns things that are constant in ODEs into function symbols.
    *
    * @example Turns v>0, a>0 |- [v'=a;]v>0, a>0 into v>0, a()>0 |- [v'=a();]v>0, a()>0
    * @return The tactic.
    */
  def Dconstify(inner: BelleExpr): DependentPositionTactic = TacticFactory.anon ((pos: Position, seq: Sequent) => {
    val (ctx, expr) = seq.at(pos)
    expr match {
      case Box(ode@ODESystem(_, _), p) =>
        val consts = (StaticSemantics.freeVars(p) -- StaticSemantics.boundVars(p) -- StaticSemantics.boundVars(ode)).toSet.filter(_.isInstanceOf[BaseVariable])
        val ctxBoundConsts = StaticSemantics.boundVars(ctx(True)).intersect(consts)
        if (ctxBoundConsts.isEmpty) constify(consts, inner)
        else throw new TacticInapplicableFailure("Unable to constify in context " + ctx + ", because it binds " + ctxBoundConsts.toSet[Variable].map(_.prettyString).mkString(","))
      case Diamond(ode@ODESystem(_, _), p) =>
        val consts = (StaticSemantics.freeVars(p) -- StaticSemantics.boundVars(ode)).toSet.filter(_.isInstanceOf[BaseVariable])
        val ctxBoundConsts = StaticSemantics.boundVars(ctx(True)).intersect(consts)
        if (ctxBoundConsts.isEmpty) constify(consts, inner)
        else throw new TacticInapplicableFailure("Unable to constify in context " + ctx + ", because it binds " + ctxBoundConsts.toSet[Variable].map(_.prettyString).mkString(","))
      case e => throw new TacticInapplicableFailure("Dconstify only applicable to box/diamond ODEs, but got " + e.prettyString)
    }
  })

  /** Turns all `consts` into function symbols. */
  def constify(consts: Set[Variable], inner: BelleExpr): DependentTactic = TacticFactory.anon ((seq: Sequent) => {
    consts.foldLeft[BelleExpr](inner)((tactic, c) =>
      let(FuncOf(Function(c.name, c.index, Unit, c.sort), Nothing), c, tactic))
  })

 /** Add constant context into the domain constraint at a given (top-level) position by V
   * @example Turns v>0, a>0 |- [v'=a]v>0 into v>0, a>0 |- [v'=a & a>0]v>0
   */
 def DconstV : DependentPositionTactic = anon ((pos:Position,seq:Sequent) => {
    require(pos.isTopLevel, "DconstV only at top-level positions")
    val dom = seq.sub(pos) match {
      case Some(Box(ODESystem(_, dom), p)) => dom
      case Some(Diamond(ODESystem(_, dom), p)) => dom
      case Some(e) => throw new TacticInapplicableFailure("DconstV only applicable to box/diamond ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }

    //The constant context
    val constCtxt = TacticHelper.propertiesOfConstants(seq,pos.checkTop)
    if(constCtxt.isEmpty)
      skip
    else {
      val newDom = constCtxt.foldRight(dom)((x, y) => And(x, y))
      dR(newDom)(pos) <( skip,
         //propositional proof should be sufficient here
        (boxAnd(1) & andR(1)<(V(1) & id,skip))*constCtxt.length &
         diffWeakenG(1) & implyR(1) & id)
    }
  })

  /** Simplify a top-level succedent box ODE with the domain constraint
    * This uses the default simplifier configuration
    * @example Turns |- [v'=a & a>0](a>0&v>0) into |- [v'=a & a>0]v>0
    */
  def domSimplify : DependentPositionTactic = anon ((pos:Position,seq:Sequent) => {
    require(pos.isTopLevel && pos.isSucc, "domSimplify currently only works at top-level succedents")

    val (ode,post) = seq.sub(pos) match {
      case Some(Box(ode @ ODESystem(_,_), post)) => (ode,post)
      case Some(e) => throw new TacticInapplicableFailure("domSimplify only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }

    //todo: How to exactly simulate behavior of andL('L)*?? flattenConjunctions doesn't match it
    val ctx = proveBy(Sequent(IndexedSeq(ode.constraint),IndexedSeq(False)), SaturateTactic(andL('L))).subgoals(0).ante

    val (f,propt) = SimplifierV3.simpWithDischarge(ctx,post,SimplifierV3.defaultFaxs,SimplifierV3.defaultTaxs)
    //val (f,propt) = SimplifierV3.simpWithDischarge(flattenConjunctions(ode.constraint).toIndexedSeq,post,SimplifierV3.defaultFaxs,SimplifierV3.defaultTaxs)
    propt match {
      case None => skip
      case Some(pr) =>
        cutR (Box (ode, f) ) (pos) < (skip,
        cohideR (pos) & implyR(1) & DW(1) & monb & implyR(1) & implyRi & SaturateTactic(andL('L)) & equivifyR(1) &
        commuteEquivR(1) & by(pr)
        )
    }
  })

  /** DG: Differential Ghost add auxiliary differential equations with extra variables `y'=a*y+b`.
    * `[x'=f(x)&q(x)]p(x)` reduces to `\exists y [x'=f(x),y'=a*y+b&q(x)]p(x)`.
    *
    * @example {{{
    *         |- \exists y [{x'=2,y'=0*y+1}]x>0
    *         ---------------------------------- DG("{y'=0*y+1}".asDifferentialProgram)(1)
    *         |- [{x'=2}]x>0
    * }}}
    * @example {{{
    *         |- \exists y [{x'=2,y'=f()*y+g() & x>=0}]x>0
    *         --------------------------------------------- DG("{y'=f()*y+g()}".asDifferentialProgram)(1)
    *         |- [{x'=2 & x>=0}]x>0
    * }}}
    * @param ghost A differential program of the form y'=a*y+b or y'=a*y or y'=b.
    * @see [[dG()]]
    * @todo generalize to diamond ODEs since it's an equivalence
    */
  private def DG(ghost: DifferentialProgram): DependentPositionTactic = anon ((pos: Position, sequent: Sequent) => {
    val (y:Variable, a:Term, b:Term) = try {
      DifferentialHelper.parseGhost(ghost)
    } catch {
      case ex: CoreException =>
        val wrongShapeStart = ex.getMessage.indexOf("b(|y_|)~>")
        throw new InputFormatFailure(ex.getMessage.substring(wrongShapeStart + "b(|y_|)~>".length).stripSuffix(")") +
          " is not of the expected shape a*y+b, please provide a differential program of the shape y'=a*y+b.")
    }

    sequent.sub(pos) match {
      case Some(fml@Box(ode@ODESystem(c, h), p)) if !StaticSemantics(ode).bv.contains(y) &&
        !StaticSemantics.symbols(a).contains(y) && !StaticSemantics.symbols(b).contains(y) &&
        !StaticSemantics.freeVars(fml).contains(y) =>

        //SOUNDNESS-CRITICAL: DO NOT ALLOW SINGULARITIES IN GHOSTS.
        //@TODO This is a bit hacky. We should either:
        //  1) try to cut <(nil, dI(1)) NotEqual(v, Number(0)) before doing
        //     the ghost, and only check for that here; or
        //  2) insist on NotEqual and provide the user with an errormessage.
        //But ultimately, we need a systematic way of checking this in the
        //core (last-case resort could always just move this check into the core and apply
        //it whenever DG differential ghost is applied, but that's pretty
        //hacky and won't suffice).
        val singular = {
          val evDomFmls = flattenConjunctions(h)
          (FormulaTools.singularities(a) ++ FormulaTools.singularities(b)).filter(v =>
            !evDomFmls.contains(Less(v, Number(0)))     &&
            !evDomFmls.contains(Less(Number(0), v))     &&
            !evDomFmls.contains(Greater(v, Number(0)))  &&
            !evDomFmls.contains(Greater(Number(0), v))  &&
            !evDomFmls.contains(NotEqual(v, Number(0))) &&
            !evDomFmls.contains(Greater(Number(0), v))
          )
        }

        if (singular.nonEmpty)
          throw new IllFormedTacticApplicationException("Possible singularities during DG(" + ghost + ") will be rejected: " +
            singular.mkString(",") + " in\n" + sequent.prettyString +
            "\nWhen dividing by a variable v, try cutting v!=0 into the evolution domain constraint"
          )

        (a, b) match {
          case (Number(n), _) if n == 0 =>
            val subst = (us: Option[Subst]) => us.getOrElse(throw new UnsupportedTacticFeature("DG expects substitution result from unification")) ++ RenUSubst(
              (Variable("y_",None,Real), y) ::
                (UnitFunctional("b", Except(Variable("y_", None, Real)::Nil), Real), b) :: Nil)
            useAt(Ax.DGC, PosInExpr(0::Nil), subst)(pos)
          case (_, Neg(Number(n))) =>
            val subst = (us: Option[Subst]) => us.getOrElse(throw new UnsupportedTacticFeature("DG expects substitution result from unification")) ++ RenUSubst(
              (Variable("y_",None,Real), y) ::
                (UnitFunctional("a", Except(Variable("y_", None, Real)::Nil), Real), a) ::
                (UnitFunctional("b", Except(Variable("y_", None, Real)::Nil), Real), Number(-n)) :: Nil)
            useAt(Ax.DGa, PosInExpr(0::Nil), subst)(pos)
          case _ =>
            val subst = (us: Option[Subst]) => us.getOrElse(throw new UnsupportedTacticFeature("DG expects substitution result from unification")) ++ RenUSubst(
              (Variable("y_",None,Real), y) ::
                (UnitFunctional("a", Except(Variable("y_", None, Real)::Nil), Real), a) ::
                (UnitFunctional("b", Except(Variable("y_", None, Real)::Nil), Real), b) :: Nil)
            useAt(Ax.DGa, PosInExpr(0::Nil), subst)(pos)
        }

      case Some(Box(ode@ODESystem(c, h), p)) if StaticSemantics(ode).bv.contains(y) =>
        throw new InputFormatFailure(
          "Differential ghost " + y + " of " + ghost + " is not new but already has a differential equation in " + ode + ".\nChoose a new name for the differential ghost.")

      case Some(Box(ode@ODESystem(c, h), p)) if StaticSemantics.symbols(a).contains(y) || StaticSemantics.symbols(b).contains(y) =>
        throw new InputFormatFailure(
          "Differential ghost " + y + " occurs nonlinearly or in the wrong place of the new differential equation " + ghost + ".\nChoose a differential equation " + y + "'=a*" + y + "+b that is linear in the differential ghost.")

      case Some(Box(ode@ODESystem(c, h), p)) if StaticSemantics(ode).fv.contains(y) =>
        throw new InputFormatFailure(
          "Differential ghost " + y + " of " + ghost + " is not new but already read in the differential equation " + ode + ".\nChoose a new name for the differential ghost.")

      case Some(Box(ode@ODESystem(c, h), p)) if StaticSemantics(p).fv.contains(y) =>
        throw new InputFormatFailure(
          "Differential ghost " + y + " of " + ghost + " is not new but already read in the postcondition " + p + ".\nChoose a new name for the differential ghost.")

      case Some(e) => throw new TacticInapplicableFailure("DG only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
    }
  })

  /** [[DifferentialEquationCalculus.dG]] */
  private[btactics] def dG(ghost: DifferentialProgram, r: Option[Formula]): DependentPositionTactic = anon (
      (pos: Position, sequent: Sequent) => sequent.sub(pos) match {
        case Some(Box(ODESystem(_, h), p)) =>
          val (_, a: Term, b: Term) = try {
            DifferentialHelper.parseGhost(ghost)
          } catch {
            case ex: CoreException =>
              val wrongShapeStart = ex.getMessage.indexOf("b(|y_|)~>")
              throw new InputFormatFailure(ex.getMessage.substring(wrongShapeStart + "b(|y_|)~>".length).stripSuffix(")") +
                " is not of the expected shape a*y+b, please provide a differential program of the shape y'=a*y+b.")
          }
          val singular = {
            val evDomFmls = flattenConjunctions(h)
            (FormulaTools.singularities(a) ++ FormulaTools.singularities(b)).filter(v =>
              !evDomFmls.contains(Less(v, Number(0)))     &&
              !evDomFmls.contains(Less(Number(0), v))     &&
              !evDomFmls.contains(Greater(v, Number(0)))  &&
              !evDomFmls.contains(Greater(Number(0), v))  &&
              !evDomFmls.contains(NotEqual(v, Number(0))) &&
              !evDomFmls.contains(Greater(Number(0), v))
            )
          }
          val cutSingularities = if (singular.nonEmpty) {
            singular.map(t => ?(dC(NotEqual(t, Number(0)))(pos) <(skip, ODE(pos) & done))).reduce(_ & _)
          } else skip
          val doGhost = r match {
            case Some(rr) if r != sequent.sub(pos ++ PosInExpr(1::Nil)) =>
              DG(ghost)(pos) & (DW(pos ++ PosInExpr(0::Nil)) & transform(rr)(pos ++ PosInExpr(0::1::Nil)) | DebuggingTactics.error(
                "Formula\n  " + rr.prettyString + "\ndoes not imply postcondition\n  " + p.prettyString +
                  "\nor necessary facts might not be preserved automatically; try to preserve with differential cuts before using dG in\n",
                new BelleUserCorrectableException(_) {}
              ))
            case _ => DG(ghost)(pos) //@note no r or r==p
          }
          cutSingularities & doGhost
        case Some(e) => throw new TacticInapplicableFailure("dG only applicable to box ODEs, but got " + e.prettyString)
        case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
      })

  /**
    * Removes the left-most DE from a system of ODEs:
    * {{{
    *   [v'=a,t'=1 & q]p
    *   ---------------------- dGi
    *   [x'=v,v'=a,t'=1 & q]p
    * }}}
    */
  @Tactic(names="Inverse Differential Ghost",
    codeName="dGi",  // todo: rename the tactic directly
    premises="Γ |- [x'=f(x) & Q]P, Δ",
    conclusion="Γ |- ∃y [x'=f(x),E & Q]P, Δ",
    displayLevel="browse")
  val inverseDiffGhost: DependentPositionTactic = anon ((pos: Position, s: Sequent) => {
    val polarity = (if (pos.isSucc) 1 else -1) * FormulaTools.polarityAt(s(pos.top), pos.inExpr)
    s.sub(pos) match {
      case Some(f@Box(ODESystem(DifferentialProduct(y_DE: AtomicODE, _), _), _)) if polarity > 0 =>
        //Cut in the right-hand side of the equivalence in the [[axiomName]] axiom, prove it, and then performing rewriting.
        TactixLibrary.cutAt(Forall(y_DE.xp.x::Nil, f))(pos) <(
          HilbertCalculus.useExpansionAt(Ax.DGi)(pos)
          ,
          (if (pos.isSucc) TactixLibrary.cohideR(pos.top) else TactixLibrary.cohideR('Rlast)) &
            HilbertCalculus.useAt(Ax.alle)(1, PosInExpr((if (pos.isSucc) 0 else 1) +: pos.inExpr.pos)) &
            TactixLibrary.useAt(Ax.implySelf)(1) & TactixLibrary.closeT & DebuggingTactics.done
        )
      case Some(Box(ODESystem(DifferentialProduct(y_DE: AtomicODE, c), q), p)) if polarity < 0 =>
        //@note must substitute manually since DifferentialProduct reassociates (see cutAt) and therefore unification won't match
        val subst = (_: Option[TactixLibrary.Subst]) =>
          RenUSubst(
            ("y_".asTerm, y_DE.xp.x) ::
              ("b(|y_|)".asTerm, y_DE.e) ::
              ("q(|y_|)".asFormula, q) ::
              (DifferentialProgramConst("c", Except("y_".asVariable::Nil)), c) ::
              ("p(|y_|)".asFormula, p.replaceAll(y_DE.xp.x, "y_".asVariable)) ::
              Nil)

        //Cut in the right-hand side of the equivalence in the [[axiomName]] axiom, prove it, and then rewrite.
        HilbertCalculus.useAt(Ax.commaCommute, PosInExpr(1::Nil))(pos) &
          TactixLibrary.cutAt(Exists(y_DE.xp.x::Nil, Box(ODESystem(DifferentialProduct(c, y_DE), q), p)))(pos) <(
            HilbertCalculus.useAt(Ax.DGC, PosInExpr(1::Nil), subst)(pos)
            ,
            (if (pos.isSucc) TactixLibrary.cohideR(pos.top) else TactixLibrary.cohideR('Rlast)) &
              TactixLibrary.CMon(pos.inExpr) & TactixLibrary.implyR(1) &
              TactixLibrary.existsR(y_DE.xp.x)(1) & TactixLibrary.id
          )
      case Some(e) if polarity == 0 => throw new TacticInapplicableFailure("dGi only applicable in positive or negative polarity contexts")
      case Some(e) => throw new TacticInapplicableFailure("dGi only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + s.prettyString)
    }
  })

  /** @see [[HilbertCalculus.Derive.Dvar]] */
  //@todo could probably simplify implementation by picking atomic formula, using "x' derive var" and then embedding this equivalence into context by CE.
  //@todo Or, rather, by using CE directly on a "x' derive var" provable fact (z)'=1 <-> z'=1.
  //@Tactic in HilbertCalculus.Derive.Dvar same
  @Tactic(names="x'",
    conclusion="__(x)'__ = x",
    displayLevel="internal")
  private[btactics] lazy val Dvariable: DependentPositionTactic = anon ( (pos:Position, sequent:Sequent) => {

    val OPTIMIZED = true
    val axiom: AxiomInfo = Ax.DvariableCommutedAxiom
    val (keyCtx:Context[_],keyPart) = axiom.formula.at(PosInExpr(1::Nil))

    /** Finds the first parent of p in f that is a formula. Returns p if f at p is a formula. */
    @tailrec
    def formulaPos(f: Formula, p: PosInExpr): PosInExpr = {
      f.sub(p) match {
        case Some(_: Formula) => p
        case _ => formulaPos(f, p.parent)
      }
    }

    sequent.sub(pos) match {
        case Some(Differential(x: Variable)) =>
          if (OPTIMIZED) {
            logger.debug("Dvariable " + keyPart + " on " + x)
            val fact = UnificationMatch.apply(keyPart, Differential(x)).toForward(axiom.provable)
            CEat(fact)(pos)
          } else {
            val withxprime: Formula = sequent.replaceAt(pos, DifferentialSymbol(x)).asInstanceOf[Formula]
            val axiom = s"\\forall ${x.prettyString} (${x.prettyString})' = ${x.prettyString}'".asFormula
            cutLR(withxprime)(pos.topLevel) <(
              /* use */ skip,
              /* show */ cohide(pos.top) & CMon(formulaPos(sequent(pos.top), pos.inExpr)) & cut(axiom) <(
              useAt(Ax.alle)(-1) & eqL2R(-1)(1) & useAt(Ax.implySelf)(1) & close,
              cohide('Rlast) & byUS(Ax.DvariableAxiom))
              )
          }
        case Some(e) => throw new TacticInapplicableFailure("Dvariable only applicable to Differentials, but got " + e.prettyString)
        case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
      }
  })

  /**
   * Unpacks the evolution domain of an ODE at time zero. Useful for proofs that rely on contradictions with other
   * conditions at time zero preventing continuous evolution in the ODE.
   * {{{
   *  x<0, x>=0 |- [x'=3,y'=2 & x>=0]y>0
   *  -----------------------------------diffUnpackEvolutionDomainInitially(1)
   *        x<0 |- [x'=3,y'=2 & x>=0]y>0
   * }}}
   */
  @Tactic(names="Unpack evolution domain",
    codeName="diffUnpackEvolDomain", // todo: rename the tactic directly
    premises="Γ, Q |- [x'=f(x) & Q]P, Δ",
    conclusion="Γ |- [x'=f(x) & Q]P, Δ",
    displayLevel="browse")
  lazy val diffUnpackEvolutionDomainInitially: DependentPositionTactic = anon ((pos: Position, sequent: Sequent) => sequent.sub(pos) match {
    case Some(Box(ODESystem(_, q), _)) =>
      require(pos.isSucc && pos.isTopLevel, "diffUnpackEvolDomain only at top-level in succedent")
      cut(q) <(
        /* use */ skip,
        /* show */ DI(pos) & implyR(pos) & closeIdWith('Llast)
        )
    case Some(e) => throw new TacticInapplicableFailure("diffUnpackEvolDomain only applicable to box ODEs, but got " + e.prettyString)
    case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
  })

  /** [[DifferentialEquationCalculus.dW]]. diffWeaken by diffCut(consts) <(diffWeakenG, V&close) */
  @Tactic(names="dW",
          codeName="dW", // todo: rename the tactic directly
          longDisplayName="Differential Weaken",
          premises="Γ<sub>const</sub>, Q |- P, Δ<sub>const</sub>",
          conclusion="Γ |- [x'=f(x) & Q]P, Δ",
          contextPremises="Γ |- C( ∀x (Q→P) ), Δ",
          contextConclusion="Γ |- C( [x'=f(x) & Q]P ), Δ",
          displayLevel="all", revealInternalSteps=true)
  private[btactics] lazy val diffWeaken: DependentPositionTactic = anon ((pos: Position, sequent: Sequent) =>
    if (pos.isAnte) {
      throw new TacticInapplicableFailure("dW only in succedent")
    } else if (!pos.isTopLevel) {
      DW(pos) & abstractionb(pos)
    } else sequent.sub(pos) match {
      case Some(Box(a: ODESystem, q)) =>
        require(pos.isTopLevel && pos.isSucc, "dW only at top level in succedent")

        val primedVars = DifferentialHelper.getPrimedVariables(a).toSet
        val constFacts = sequent.zipWithPositions.flatMap({
          case (fml, pos) =>
            if (pos.isAnte) FormulaTools.conjuncts(fml)
            else FormulaTools.conjuncts(fml).map(Not)
        }).filter(f => StaticSemantics.freeVars(f).intersect(primedVars).isEmpty).reduceRightOption(And)

        val p = constFacts match {
          case Some(f) => And(a.constraint, f)
          case None => a.constraint
        }

        constFacts.map(DifferentialEquationCalculus.dC(_)(pos) &
          // diffCut may not introduce the cut if it is already in there; diffCut changes the position in the show branch to 'Rlast
          Idioms.doIf(_.subgoals.size == 2)(<(skip, V('Rlast) & prop & done))).getOrElse(skip) & DW(pos) & G(pos) & implyR('R, Imply(p, q))
      case Some(e) => throw new TacticInapplicableFailure("dW only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
    }
  )

  /** [[DifferentialEquationCalculus.dWPlus]]. diffWeaken preserving all initial facts and mimicking the initial sequent shape. */
  @Tactic(names=("dW+", "dWplus"), // Initial State-Preserving Differential Weaken
    codeName="dWplus", // todo: rename the tactic directly
    longDisplayName="Differential Weaken",
    premises="Γ<sub>0</sub>, Q |- P, Δ<sub>0</sub>",
    conclusion="Γ |- [x'=f(x) & Q]P, Δ",
    contextPremises="Γ |- C( ∀x (Q→P) ), Δ",
    contextConclusion="Γ |- C( [x'=f(x) & Q]P ), Δ",
    displayLevel="browse", revealInternalSteps=true)
  private[btactics] lazy val diffWeakenPlus: DependentPositionTactic = anon ((pos: Position, sequent: Sequent) =>
    if (pos.isAnte) {
      throw new TacticInapplicableFailure("dW+ only in succedent")
    } else if (!pos.isTopLevel) {
      DW(pos) & abstractionb(pos)
    } else sequent.sub(pos) match {
      case Some(box@Box(a: ODESystem, p)) =>
        require(pos.isTopLevel && pos.isSucc, "dW+ only at top level in succedent")

        val primedVars = DifferentialHelper.getPrimedVariables(a)

        val rewriteExistingGhosts = sequent.ante.zipWithIndex.filter({
          case (Equal(l: Variable, r: Variable), _) => primedVars.contains(r) && !primedVars.contains(l)
          case _ => false
        }).reverse.map({ case (_, i) => exhaustiveEqR2L(AntePosition.base0(i)) & hideL(AntePosition.base0(i)) }).
          reduceOption[BelleExpr](_&_).getOrElse(skip)

        val storeInitialVals = anon ((seq: Sequent) => {
          val symbols = seq.ante.flatMap(StaticSemantics.symbols) ++ seq.succ.patch(pos.index0, Nil, 1).flatMap(StaticSemantics.symbols)
          val storePrimedVars = primedVars.filter(symbols.contains)
          storePrimedVars.map(discreteGhost(_)(pos)).reduceOption[BelleExpr](_&_).getOrElse(skip) &
            (exhaustiveEqR2L('Llast) & hideL('Llast))*storePrimedVars.size
        })

        def cutFmls(seq: Sequent): (List[Formula], List[Formula]) = {
          val bv = StaticSemantics.boundVars(a)
          (seq.ante.flatMap({ fml =>
            if (fml != box && StaticSemantics.freeVars(fml).intersect(bv).isEmpty) Some(fml)
            else None
          }).toList,
          seq.succ.flatMap({ fml =>
            if (fml != box && StaticSemantics.freeVars(fml).intersect(bv).isEmpty) Some(Not(fml))
            else None
          }).toList)
        }

        val cutAndDW = anon ((seq: Sequent) => {
          //@note filter to include only formulas that are rewritten to initial values
          val (anteCuts, succCuts) = cutFmls(seq)
          val cuts = anteCuts ++ succCuts
          val odeAfterCut = if (cuts.isEmpty) box else Box(ODESystem(a.ode, And(a.constraint, cuts.reduceRight(And))), p)
          //@note implyRi+implyR to move Q last in succedent
          val dw = diffWeakenG('R, odeAfterCut) & implyR(1) & andL('Llast)*cuts.size& notL('Llast)*succCuts.size &
            implyRiX(AntePos(0), SuccPos(0)) & implyR(1)
          if (cuts.isEmpty) dw
          else diffCut(cuts.reduceRight(And))('R, box) <(
            skip,
            V('Rlast) &
              (if (anteCuts.nonEmpty) (andR('Rlast) <(closeIdWith('Rlast) & done, skip))*(anteCuts.size-1) &
                (if (succCuts.nonEmpty) andR('Rlast) <(closeIdWith('Rlast) & done, skip) else closeIdWith('Rlast)) else skip) &
              (if (succCuts.nonEmpty) (andR('Rlast) <(notR('Rlast) & closeIdWith('Llast) & done, skip))*(succCuts.size-1) &
                notR('Rlast) & closeIdWith('Llast) else skip) & done
          ) & dw
        })

        rewriteExistingGhosts & storeInitialVals & cutAndDW
      case Some(e) => throw new TacticInapplicableFailure("dWplus only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
    }
  )

  /** diffWeaken by DW & G
    * @see [[TactixLibrary.DW]]
    * @see [[TactixLibrary.G]]
    */
  lazy val diffWeakenG: DependentPositionTactic = anon ((pos: Position, sequent: Sequent) => sequent.sub(pos) match {
    case Some(Box(_: ODESystem, p)) =>
      require(pos.isTopLevel && pos.isSucc, "diffWeakenG only at top level in succedent")
      cohide(pos.top) & DW(1) & G(1)
    case Some(e) => throw new TacticInapplicableFailure("diffWeakenG only applicable to box ODEs, but got " + e.prettyString)
    case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
  })

  //A user-friendly error message displayed when ODE can't find anything useful to do.
  private val failureMessage = "ODE automation was neither able to prove the postcondition invariant nor automatically find new ODE invariants."+
    " Try annotating the ODE with additional invariants or refining the evolution domain with a differential cut."

  /** Assert LZZ succeeds at a certain position. */
  lazy val lzzCheck: BuiltInPositionTactic = {
    def constConditions(formulas: IndexedSeq[Formula], taboo: SetLattice[Variable]): IndexedSeq[Formula] = {
      formulas.filter(StaticSemantics.freeVars(_).intersect(taboo).isEmpty)
    }

    DebuggingTactics.assert((invSeq: Sequent, invPos: Position) => {
      invSeq.sub(invPos) match {
        case Some(Box(ode: ODESystem, invCandidate)) => ToolProvider.invGenTool() match {
          case Some(invTool) =>
            //@todo constant conditions at the sub position
            val topFml = invSeq.sub(invPos.top).get.asInstanceOf[Formula]
            val consts = constConditions(
              invSeq.ante.flatMap(FormulaTools.conjuncts),
              StaticSemantics(topFml).bv).reduceRightOption(And)
            val strengthenedCandidate = consts match {
              case Some(c) => And(c, invCandidate)
              case None => invCandidate
            }
            try {
              invTool.lzzCheck(ode, strengthenedCandidate)
            } catch {
              // cannot falsify for whatever reason (timeout, ...), so continue with the tactic
              case _: Exception => true
            }
          case _ => true
        }
        case _ => false
      }
    }, "Invariant fast-check failed", new TacticInapplicableFailure(_))
  }

  /** Invariance check
    * @return Returns True if it determines that the only possibilty is for postcondition to
    *         be invariant at the position it is called (either a loop invariant or ODE invariant)
    *         This can be used to prevent (unnecessary) invariant generation for loops or ODEs from happening
    *         Return False in all other cases (including when the sequent or position are not of the expected shape)
    */
  private def isInvariantQuestion(pos: Position, seq: Sequent): Boolean = {
    def isInvQuestion(a: Program, p: Formula, prgAssumptions: Formula): Boolean = {
      val assms = seq.ante.flatMap(flattenConjunctions).toList
      //Track constant assumptions separately
      val odeBV = StaticSemantics.boundVars(a)
      val (assmsConst, assmsRest) = assms.partition(StaticSemantics.freeVars(_).intersect(odeBV).isEmpty)
      val conjConst = assmsConst.foldLeft(prgAssumptions)(And)
      val conjRest = assmsRest.foldLeft[Formula](True)(And)
      proveBy(Imply(conjConst, Equiv(conjRest, p)), ?(timeoutCEXQE)).isProved
    }

    seq.sub(pos) match {
      case Some(Box(ode: ODESystem, post)) => post.isFOL && isInvQuestion(ode, post, ode.constraint)
      case Some(Box(l: Loop, post)) => post.isFOL && isInvQuestion(l, post, True)
      case _ => false
    }
  }

  /** Invariance check
    * @return Executes t if it detects a purely invariance question (for all subgoals) otherwise execute f
    */
  def invCheck(t: BelleExpr, f: BelleExpr): DependentPositionTactic = anon ((pos: Position) => {
    doIfElse(pr => pr.subgoals.forall(s => isInvariantQuestion(pos, s)))(t, f)
  })

  /** ODE counterexample finder
    * @return Leaves False as the only subgoal if it finds a counterexample to the ODE question at the position it is called
    *         Succeeds in all other cases (including when the sequent or position are not of the expected shape)
    */
  @Tactic("cexODE")
  val cexODE: DependentPositionTactic = anon ((pos: Position, seq:Sequent) => {
    if (!(pos.isSucc && pos.isTopLevel && pos.checkSucc.index0 == 0 && seq.succ.length==1)) {
      //todo: currently only works if there is exactly one succedent
      logger.warn("ODE counterexample not called at top-level succedent")
      skip
    }

    else {
      if (ToolProvider.invGenTool().isEmpty) {
        logger.warn("ODE counterexample requires an InvGenTool, but got None")
        skip
      } else {
        val tool = ToolProvider.invGenTool().get

        seq.sub(pos) match {
          case Some(Box(ode: ODESystem, post)) =>
            try {
              tool.refuteODE(ode, seq.ante, post) match {
                case None => skip
                case Some(_) => cut(False) < (closeF, cohideR('Rlast))
              }
            } catch {
              // cannot falsify for whatever reason (timeout, ...), so continue with the tactic
              case ex: Throwable => skip
            }
          case _ =>
            logger.warn("ODE counterexample not called at box ODE in succedent")
            skip
        }
      }
    }
  })

  /** Tries to instantiate the evolution domain fact with the ODE duration (assumes monotonicity). */
  lazy val endODEHeuristic: BelleExpr = anon ((seq: Sequent) => {
    val instantiators = (seq.ante.indices.map(AntePosition.base0(_)) ++ seq.succ.indices.map(SuccPosition.base0(_))).
      flatMap(pos => {
        Idioms.mapSubpositions(pos, seq, {
          case (Forall((s@BaseVariable("s_", _, Real))::Nil, Imply(And(
          LessEqual(_, BaseVariable("s_", _, Real)),
          LessEqual(BaseVariable("s_", _, Real), t@BaseVariable("t_", _, Real))), _)), pp: Position) =>
            val polarity = (if (pos.isSucc) 1 else -1) * FormulaTools.polarityAt(seq(pp.top), pp.inExpr)
            if (polarity < 0) Some(allL(s, t)(pp) & pp.parent.flatMap(_.parent).map(SimplifierV3.simplify(_)).getOrElse(skip))
            else None
          case _ => None
        })
    })

    instantiators.reduceOption[BelleExpr](_ & _).getOrElse(skip)
  })

  /**
    * @see [[TactixLibrary.ODE]]
    * @author Andre Platzer
    * @author Nathan Fulton
    * @author Stefan Mitsch
    * @note Compatibility tactic for Z3 ([[DifferentialTactics.odeInvariant]] not supported with Z3).
    */
  lazy val ODE: DependentPositionTactic = anon ((pos: Position, seq: Sequent) => seq.sub(pos) match {
    case Some(Box(sys@ODESystem(ode, q), _)) =>
      lazy val recurseODE = ODE(useOdeInvariant = false, introduceStuttering = true,
        //@note abort if unchanged
        DebuggingTactics.assert((sseq: Sequent, ppos: Position) => !sseq.sub(ppos ++ PosInExpr(0::Nil)).contains(sys),
          failureMessage, new BelleNoProgress(_)
        )(pos) &
          (anon ((ppos: Position, sseq: Sequent) => sseq.sub(ppos) match {
            case Some(ODESystem(_, extendedQ)) =>
              if (q == True && extendedQ != True) useAt(Ax.trueAnd)(ppos ++
                PosInExpr(1 +: FormulaTools.posOf(extendedQ, q).getOrElse(PosInExpr.HereP).pos.dropRight(1)))
              else skip
          })) (pos ++ PosInExpr(0 :: Nil))
      )(pos)

      if (pos.isTopLevel) {
        proveWithoutCuts(false)(pos) |
        solve(pos) & ?(endODEHeuristic & QE) |
        recurseODE
      } else {
        //@note diffInd in context won't fail even if unprovable in the end; try solve first to support the usual examples
        solve(pos) & ?(endODEHeuristic & QE) |
        proveWithoutCuts(false)(pos) |
        recurseODE
      }
    case Some(e) => throw new TacticInapplicableFailure("ODE only applicable to box ODEs, but got " + e.prettyString)
    case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
  })

  /** Compatibility ODE invariance tactics prior to [[DifferentialTactics.odeInvariant]] */
  private def compatibilityFallback(pos: Position, isOpen: Boolean): BelleExpr =
    lzzCheck(pos) &
      (if (isOpen) {
        openDiffInd(pos) | DGauto(pos) //> TODO: needs updating
      } else {
        diffInd()(pos)       | // >= to >=
          DGauto(pos)          |
          dgZeroMonomial(pos)  | //Equalities
          dgZeroPolynomial(pos)  //Equalities
      })

  /** Proves ODE invariance properties. */
  private val proveInvariant = anon ((pos: Position, seq: Sequent) => {
    val post: Formula = seq.sub(pos) match {
      case Some(Box(ode: ODESystem, pf)) => pf
      case Some(e) => throw new TacticInapplicableFailure("proveInvariant only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }
    val isOpen = post match {
      case  _: Greater => true
      case _: Less => true
      case _ => false
    }
    TactixLibrary.odeInvariant(pos) | compatibilityFallback(pos, isOpen)
  })

  /** Tries to prove ODE properties without invariant generation or solving. */
  private def proveWithoutCuts(useOdeInvariant: Boolean) = anon ((pos: Position) => {
    SaturateTactic(boxAnd(pos) & andR(pos)) &
      onAll((anon ((pos: Position, seq: Sequent) => {
        val (ode:ODESystem, post:Formula) = seq.sub(pos) match {
          case Some(Box(ode: ODESystem, pf)) => (ode, pf)
          case Some(e) => throw new TacticInapplicableFailure("proveWithoutCuts only applicable to box ODEs, but got " + e.prettyString)
          case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
        }

        val bounds = StaticSemantics.boundVars(ode.ode).symbols //@note ordering irrelevant, only intersecting/subsetof
        val frees = StaticSemantics.freeVars(post).symbols      //@note ordering irrelevant, only intersecting/subsetof

        val isOpen = post match {
          case  _: Greater => true
          case _: Less => true
          case _ => false
        }

        //@note diffWeaken will already include all cases where V works, without much additional effort.
        (if (frees.intersect(bounds).subsetOf(StaticSemantics.freeVars(ode.constraint).symbols))
          diffWeaken(pos) & QE(Nil, None, Some(Integer.parseInt(Configuration(Configuration.Keys.ODE_TIMEOUT_FINALQE)))) & done else fail
          ) | (if (useOdeInvariant) proveInvariant(pos)
        else compatibilityFallback(pos, isOpen))
      })) (pos))
  })

  private def ODE(useOdeInvariant: Boolean, introduceStuttering: Boolean, finish: BelleExpr): DependentPositionTactic = anon ((pos: Position, seq: Sequent) => {
    val invariantCandidates = try {
      InvariantGenerator.differentialInvariantGenerator(seq,pos)
    } catch {
      case err: Exception =>
        logger.warn("Failed to produce a proof for this ODE. Underlying cause: ChooseSome: error listing options " + err)
        Stream[GenProduct]()
    }

    //Adds an invariant to the system's evolution domain constraint and tries to establish the invariant via proveWithoutCuts.
    //Fails if the invariant cannot be established by proveWithoutCuts.
    val addInvariant = ChooseSome(
      () => invariantCandidates.iterator,
      (prod: GenProduct) => prod match {
        case (inv, _) =>
          DebuggingTactics.debug(s"[ODE] Trying to cut in invariant candidate: $inv") &
          /*@note diffCut skips previously cut in invs, which means <(...) will fail and we try the next candidate */
          diffCut(inv)(pos) <(skip, proveInvariant(pos) & done)
      }
    )

    //If lateSolve is true then diffSolve will be run last, if at all.
    val insistOnProof = pos.isTopLevel //@todo come up wtih better heuristic for determining when to insist on a proof being completed. Solvability is a pretty decent heuristic.

    /** Introduces stuttering assignments for each BV of the ODE */
    val stutter = anon ((pos: Position, seq: Sequent) => seq.sub(pos) match {
      case Some(Box(a: ODESystem, _)) =>
        val primedVars = StaticSemantics.boundVars(a).toSet[Variable].filter(_.isInstanceOf[BaseVariable])
        primedVars.map(DLBySubst.stutter(_)(pos ++ PosInExpr(1::Nil))).reduceOption[BelleExpr](_&_).getOrElse(skip)
      case Some(e) => throw new TacticInapplicableFailure("ODE.stutter only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    })

    val unstutter = anon ((pos: Position, seq: Sequent) => seq.sub(pos) match {
      case Some(Box(a: ODESystem, _)) =>
        val primedVars = StaticSemantics.boundVars(a).toSet[Variable].filter(_.isInstanceOf[BaseVariable])
        (1 to primedVars.size).reverse.map(i => ?(assignb(pos ++ PosInExpr(List.fill(i)(1))))).
          reduceOption[BelleExpr](_&_).getOrElse(skip)
      case _ => skip
    })

    if (insistOnProof)
      proveWithoutCuts(useOdeInvariant)(pos)        |
      (addInvariant & ODE(useOdeInvariant=true, introduceStuttering,finish)(pos))    |
      splitWeakInequality(pos)<(ODE(useOdeInvariant=true, introduceStuttering,finish)(pos), ODE(useOdeInvariant=true, introduceStuttering,finish)(pos)) |
      (if (introduceStuttering) stutter(pos) & ODE(useOdeInvariant=true, introduceStuttering=false,finish)(pos) & unstutter(pos)
       else finish)
    else
      (proveWithoutCuts(useOdeInvariant)(pos) & done)   |
      (addInvariant & ODE(useOdeInvariant=true, introduceStuttering,finish)(pos) & done) |
      (splitWeakInequality(pos) <(
        ODE(useOdeInvariant=true, introduceStuttering,finish)(pos),
        ODE(useOdeInvariant=true, introduceStuttering,finish)(pos)) & done) |
      (if (introduceStuttering) stutter(pos) & ODE(useOdeInvariant=true, introduceStuttering=false, finish)(pos) & unstutter(pos) & done
       else finish)
  })

  /** Fast ODE implementation. Tries the provided `invariantCandidates`. Tactic `finish` is executed when fastODE itself cannot find a proof. */
  // was named "ODE"
  private def fastODE(invariantCandidates: => Iterator[GenProduct])(finish: BelleExpr): DependentPositionTactic = anon ((pos: Position, seq: Sequent) => {
    //Adds invariants to the system's evolution domain constraint and tries to establish them via odeInvariant.
    //Fails if the invariants cannot be established by odeInvariant.
    val addInvariant = ChooseSome(
      () => invariantCandidates,
      (prod: GenProduct) => prod match {
        case (True, Some(PegasusProofHint(true, Some("PreInv")))) =>
          val preInv = (if (pos.isAnte) seq.updated(pos.top, True) else seq.updated(pos.top, False)).toFormula
          val afterCutPos: PositionLocator = if (seq.succ.size > 1) LastSucc(0) else Fixed(pos)
          diffCut(preInv)(pos) <(
            skip,
            odeInvariant(tryHard = true, useDw = false)(afterCutPos) & done
          )
        case (True, Some(PegasusProofHint(true, Some("PostInv")))) =>
          odeInvariant(tryHard = true, useDw = true)(pos) & done
        case (True, Some(PegasusProofHint(true, Some("DomImpPost")))) =>
          DifferentialTactics.DconstV(pos) & DifferentialTactics.diffWeakenG(pos) & timeoutQE & done
        case (True, Some(PegasusProofHint(true, Some("PreDomFalse")))) =>
          diffUnpackEvolutionDomainInitially(pos) & hideR(pos) & timeoutQE & done
        case (True, Some(PegasusProofHint(true, Some("PreNoImpPost")))) =>
          throw BelleCEX("ODE postcondition does not overlap with precondition", Map.empty, seq)
        case (inv, proofHint) =>
          //@todo workaround for diffCut/useAt unstable positioning
          val afterCutPos: PositionLocator = if (seq.succ.size > 1) LastSucc(0) else Fixed(pos)
          DebuggingTactics.debug(s"[ODE] Trying to cut in invariant candidate: $inv") &
            /*@note diffCut skips previously cut in invs, which means <(...) will fail and we try the next candidate */
            diffCut(inv)(pos) <(
              skip,
              proofHint match {
                case Some(PegasusProofHint(_, Some("Barrier"))) =>
                  dgDbxAuto(afterCutPos) & done |
                    dgBarrier(afterCutPos) & done |
                    odeInvariant(tryHard = true, useDw = false)(afterCutPos) & done
                case Some(PegasusProofHint(_, Some("Darboux"))) =>
                  dgDbxAuto(afterCutPos) & done |
                    odeInvariant(tryHard = true, useDw = false)(afterCutPos) & done
                case Some(PegasusProofHint(_, Some("FirstIntegral"))) =>
                  diffInd()(afterCutPos) & done |
                    odeInvariant(tryHard = true, useDw = false)(afterCutPos) & done
                case Some(PegasusProofHint(_, _)) => odeInvariant(tryHard = true, useDw = false)(afterCutPos) & done
                case Some(AnnotationProofHint(tryHard)) => odeInvariant(tryHard = tryHard, useDw = false)(afterCutPos) & done
                case _ => odeInvariant(tryHard = false, useDw = false)(afterCutPos) & done
              }
            ) &
          // continue outside <(skip, ...) so that cut is proved before used
          (odeInvariant()(pos) & done | fastODE(invariantCandidates)(finish)(pos) /* with next option from iterator */) &
          DebuggingTactics.debug("[ODE] Inv Candidate done")
      }
    )

    addInvariant | finish
  })

  /**
    * @see [[TactixLibrary.ODE]]
    * @author Andre Platzer
    * @author Nathan Fulton
    * @author Stefan Mitsch
    */
  lazy val mathematicaSplittingODE: DependentPositionTactic = anon ((pos: Position, seq: Sequent) => {
    seq.sub(pos) match {
      case Some(Box(sys@ODESystem(_, _), And(_, _))) =>
        boxAnd(pos) & andR(pos) <(mathematicaSplittingODE(pos) & done, mathematicaSplittingODE(pos)) | mathematicaODE(pos)
      case Some(Box(_: ODESystem, _)) => mathematicaODE(pos)
      case Some(e) => throw new TacticInapplicableFailure("mathematicaSplittingODE only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }
  })

  lazy val mathematicaODE: DependentPositionTactic = anon ((pos: Position, seq: Sequent) => {
    require(pos.isSucc && pos.isTopLevel, "ODE automation only applicable to top-level succedents")

    def odeWithInvgen(sys: ODESystem, generator: Generator[GenProduct],
                      onGeneratorError: Throwable => Stream[GenProduct]): DependentPositionTactic = fastODE(
      try {
        generator(seq, pos).iterator
      } catch {
        case ex: Exception =>
          logger.warn("Failed to produce a proof for this ODE. Underlying cause: generator error listing options " + ex)
          onGeneratorError(ex).iterator
      }
    )(
      //@note aborts with error if the ODE was left unchanged -- invariant generators failed
      DebuggingTactics.assert((sseq: Sequent, ppos: Position) => !sseq.sub(ppos ++ PosInExpr(0 :: Nil)).contains(sys),
        failureMessage, new BelleNoProgress(_)
      )(pos) &
        (anon ((ppos: Position, sseq: Sequent) => sseq.sub(ppos) match {
          case Some(ODESystem(_, extendedQ)) =>
            if (sys.constraint == True && extendedQ != True) useAt(Ax.trueAnd)(ppos ++
              PosInExpr(1 :: FormulaTools.posOf(extendedQ, sys.constraint).getOrElse(PosInExpr.HereP).pos.dropRight(1)))
            else skip
          case Some(e) => throw new TacticInapplicableFailure("mathematicaODE.finish only applicable to box ODEs, but got " + e.prettyString)
          case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
        })) (pos ++ PosInExpr(0 :: Nil))
    )

    seq.sub(pos) match {
      case Some(b@Box(sys@ODESystem(_, q), _)) =>
        if (StaticSemantics.symbols(q).exists(_.name == ODEInvariance.nilpotentSolveTimeVar.name)) {
          diffWeakenPlus(pos) & timeoutQE & DebuggingTactics.done("The strongest ODE invariant has already been added to the domain constraint. Try dW/dWplus/solve the ODE, expand definitions, and simplify the arithmetic for QE to make progress in your proof.")
        } else {
          // Try to prove postcondition invariant. If we don't have an invariant generator, try hard immediately.
          (if (ToolProvider.invGenTool().isEmpty) odeInvariant(tryHard = true, useDw = true)(pos) & done
           else odeInvariant()(pos) & done) |
            // Counterexample check
            cexODE(pos) & doIf(!_.subgoals.exists(_.succ.forall(_ == False)))(
              // Some additional cases
              //(solve(pos) & ?(timeoutQE)) |
              doIfElse((_: ProvableSig) => Configuration.getBoolean(Configuration.Keys.ODE_USE_NILPOTENT_SOLVE).getOrElse(true))(ODEInvariance.nilpotentSolve(true)(pos), done) |
                ODEInvariance.dRI(pos) |
                invCheck(
                  //@todo fail immediately or try Pegasus? at the moment, Pegasus seems to not search for easier invariants
                  //assertT(_ => false ,"Detected an invariant-only question at "+seq.sub(pos)+ " but ODE automation was unable to prove it." +
                  //  "ODE invariant generation skipped.")
                  //@note ODEInvariance not yet proving all invariance questions: try if Pegasus finds simpler invariants
                  odeWithInvgen(sys, InvariantGenerator.pegasusInvariants,
                    //@note ran out of options on generator error
                    (ex: Throwable) => throw new BelleNoProgress("Detected an invariant-only question at " +
                      b.prettyString + " but ODE automation was unable to prove it.", ex)
                  )(pos)
                  ,
                  odeWithInvgen(sys, TactixLibrary.differentialInvGenerator, (_: Throwable) => Stream[GenProduct]())(pos)
                )(pos)
            )
        }
      case Some(e) => throw new TacticInapplicableFailure("ODE automation only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }
  })

  /** Splits a post-condition containing a weak inequality into an open set case and an equillibrium point case.
    * Given a formula of the form [ode]p<=q, produces two new subgoals of the forms [ode]p < q and  [ode]p=q.
    * @see http://nfulton.org/2017/01/14/Ghosts/#ghosts-for-closedclopen-sets
    * @author Nathan Fulton */
  @Tactic(names="Split weak inequality",
    premises="Γ |- [{x'=f(x) & Q}] p>q, Δ ;; Γ |- [{x'=f(x) & Q}] p=q, Δ",
    conclusion="Γ |- [{x'=f(x) & Q}] p≳q, Δ",
    displayLevel="browse")
  val splitWeakInequality : DependentPositionTactic = anon ((pos: Position, seq: Sequent) => {
    val postcondition = seq.at(pos)._2 match {
      case Box(ODESystem(_,_), p) => p
      case _ => throw new TacticInapplicableFailure("splitWeakInequality is only applicable for ODE's with weak inequalities as post-conditions.")
    }
    val (lhs, rhs, openSetConstructor) = postcondition match {
      case GreaterEqual(l,r) => (l,r,Greater)
      case LessEqual(l,r)    => (l,r,Less)
      case _                 => throw new TacticInapplicableFailure(s"splitWeakInequality Expected a weak inequality in the post condition (<= or >=) but found: ${postcondition}")
    }

    val caseDistinction = Or(openSetConstructor(lhs,rhs), Equal(lhs,rhs))

    cut(caseDistinction) <(
      orL('Llast) <(
        generalize(openSetConstructor(lhs,rhs))(1) <(skip, QE & done),
        generalize(Equal(lhs,rhs))(1) <(skip, QE & done)
      )
      ,
      (hide(pos.topLevel) & QE & done) | //@todo write a hideNonArithmetic tactic.
      DebuggingTactics.error(s"splitWeakInequality failed because $caseDistinction does not hold initially.")
    )
  })

  /* Deprecated. probably use dgDbx instead. */
  @deprecated
  def dgZeroPolynomial: DependentPositionTactic = anon ((pos: Position, seq: Sequent) => {
    val Box(ODESystem(system, constraint), property) = seq.sub(pos) match {
      case Some(b@Box(ODESystem(system, constraint), property)) => b
      case Some(e) => throw new TacticInapplicableFailure("dgZeroPolynomial only applicable to box ODEs, but got " + e.prettyString)
   case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }

    val lhs = property match {
      case Equal(term, Number(n)) if n == 0 => term
      case e => throw new TacticInapplicableFailure("Not sure what to do with shape " + e.prettyString)
    }

    val (x: Variable, derivative:Term) = system match {
      case AtomicODE(xp, t) => (xp.x, t)
      case _ => throw new TacticInapplicableFailure("Systems not currently supported by dgZeroPolynomialDerivative")
    }
    require(lhs == x, "Currently require that the post-condition is of the form x=0 where x is the primed variable in the ODE.")

    val ghostVar = "z_".asVariable
    require(!StaticSemantics.vars(system).contains(ghostVar), "fresh ghost " + ghostVar + " in " + system.prettyString) //@todo should not occur anywhere else in the sequent either...

    val negOneHalf: Term = Divide(Number(-1), Number(2))
    //Given a system of the form x'=f(x), this returns (f(x))'/x simplified so that x does not occur on the denom.
    //@note this is done because we can't ghost in something that contains a division by a possibly zero-valued variable (in this case, x).
    val xPrimeDividedByX = TacticHelper.transformMonomials(derivative, (t:Term) => t match {
      case Times(coeff, Power(v,exp)) if(v == x) =>
        Times(coeff, Power(v, Minus(exp, Number(1))))
      case Times(coeff, v:Variable) if(v==x) => coeff
      case v:Variable if(v==x) => Number(1)
      case t:Term => t
    })

    /* construct the arguments ti diff aux:
     * Deprecated. Use dgDbx instead.
    * y' = -xPrimeDividedByX/2 * y
     * x=0 <-> \exists y x*y^2=0 & y>0 */
    //@todo At some point I was not sure if this works for no exponent (i.e. x, x+x, x+x+x and so on b/c of the pattern matching in dgZero. But it does. So review dgZero and this to see what's up.
    val (ghostODE, ghostEqn) = (
      AtomicODE(DifferentialSymbol(ghostVar), Times(Times(negOneHalf,xPrimeDividedByX) , ghostVar)),
      And(
        Equal(
          Times(x, Power(ghostVar, Number(2)) ),
          Number(0)
        ),
        Greater(ghostVar, Number(0))
      )
    )

    dG(ghostODE, Some(ghostEqn))(pos) & boxAnd(pos ++ PosInExpr(0::Nil)) &
      DifferentialTactics.diffInd()(pos ++ PosInExpr(0::0::Nil)) &
      //@note would be more robust to do the actual derivation here the way it's done in [[AutoDGTests]], but I'm leaving it like this so that we can find the bugs/failures in DGauto
      DGauto(pos ++ PosInExpr(0::1::Nil)) & QE & done
  })

  /** Proves properties of the form {{{x=0&n>0 -> [{x^n}]x=0}}}
    * Deprecated. Use dgDbx instead.
    * */
  @deprecated
  def dgZeroMonomial: DependentPositionTactic = anon ((pos: Position, seq: Sequent) => {
    if (ToolProvider.algebraTool().isEmpty) throw new ProverSetupException(s"dgZeroEquilibrium requires a AlgebraTool, but got None")

    val Box(ODESystem(system, constraint), property) = seq.sub(pos) match {
      case Some(b@Box(ODESystem(system, constraint), property)) => b
      case Some(e) => throw new TacticInapplicableFailure("dgZeroMonomial only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }

    /** The lhs of the post-condition {{{lhs = 0}}} */
    val lhs = property match {
      case Equal(term, Number(n)) if n == 0 => term
      case e => throw new TacticInapplicableFailure("Not sure what to do with shape " + e.prettyString)
    }

    /** The equation in the ODE of the form {{{x'=c*x^n}}}; the n is optional.
      * @todo make this tactic work for systems of ODEs. */
    val (x: Variable, (c: Option[Term], n: Option[Term])) = system match {
      case AtomicODE(variable, equation) => (variable.x, equation match {
        case Times(c, Power(variable, n)) => (Some(c), Some(n))
        case Times(c, v:Variable) if v==variable.x => (Some(c), None)
        case Power(variable, n) => (None, Some(n))
        case v:Variable if v==variable.x => (None, None)
      })
    }
    require(lhs == x, "Currently require that the post-condition is of the form x=0 where x is the primed variable in the ODE.")

    /** The ghost variable */
    val ghostVar = "z_".asVariable
    require(!StaticSemantics.vars(system).contains(ghostVar), "fresh ghost " + ghostVar + " in " + system.prettyString) //@todo should not occur anywhere else in the sequent either...


    val (newOde: DifferentialProgram, equivFormula: Formula) = (c,n) match {
      case (Some(c), Some(n)) => (
        s"$ghostVar' = ( (-1*$c * $x^($n-1)) / 2) * $ghostVar + 0".asDifferentialProgram,
        s"$x*$ghostVar^2=0&$ghostVar>0".asFormula
      )
      case (None, Some(n)) => (
        s"$ghostVar' = ((-1*$x^($n-1)) / 2) * $ghostVar + 0".asDifferentialProgram,
        s"$x*$ghostVar^2=0&$ghostVar>0".asFormula
      )
      case (Some(c), None) => (
        s"$ghostVar' = ((-1*$c*$x) / 2) * $ghostVar + 0".asDifferentialProgram,
        s"$x*$ghostVar^2=0&$ghostVar>0".asFormula
      )
      case (None, None) => (
        s"$ghostVar' = -1 * $ghostVar + 0".asDifferentialProgram,
        s"$x * $ghostVar = 0 & $ghostVar > 0".asFormula
      )
    }

    val backupTactic = dG(newOde, Some(equivFormula))(pos) & boxAnd(pos ++ PosInExpr(0::Nil)) &
      DifferentialTactics.diffInd()(pos ++ PosInExpr(0::0::Nil)) &
      //@note would be more robust to do the actual derivation here the way it's done in [[AutoDGTests]], but I'm leaving it like this so that we can find the bugs/failures in DGauto
      DGauto(pos ++ PosInExpr(0::1::Nil)) & QE & done

    //@todo massage the other cases into a useAt.
    //@note it's more robust if we do the | backupTactic, but I'm ignore thins so that we can find and fix the bug in (this use of) useAt.
    if(c.isDefined && n.isDefined) //if has correct shape for using the derived axiom
      ??? // TactixLibrary.useAt("dgZeroEquilibrium")(1) //| backupTactic
    else
      backupTactic
  })

  /**
    * Proves Darboux properties
    * p = 0 -> [ {ODE & Q} ] p = 0
    * where Q -> p' = q p
    * (similarly for >= 0, > 0, != 0)
    * Note: this also works for fractional q, if its denominator is already in Q
    * Otherwise, DG will fail on the singularity
    * Note: this assumes that the (in)equalities are normalized to have 0 on the RHS
    * Rationale: this tactic requires a cofactor input, and so it would be surprising if it normalizes internally
    * All automation tactics around dgDbx will need to normalize their input
    */
  //todo: Awkward usubst lemma placement because this is also needed in ODEInvariance...
  private lazy val dbxCond: ProvableSig = remember("((-g_())*y_()+0)*(z_())^2 + y_()*(2*z_()^(2-1)*(g_()/2*z_()+0))=0".asFormula,QE,namespace).fact

  lazy val dbxLeqRw: ProvableSig = remember("(p() & y_() > 0) & y_() * z_() <= 0 -> z_() <= 0".asFormula,QE,namespace).fact
  lazy val dbxGeqRw: ProvableSig = remember("(p() & y_() > 0) & y_() * z_() >= 0 -> z_() >= 0".asFormula,QE,namespace).fact
  lazy val dbxLtRw: ProvableSig = remember("(p() & y_() > 0) & y_() * z_() < 0 -> z_() < 0".asFormula,QE,namespace).fact
  lazy val dbxGtRw: ProvableSig = remember("(p() & y_() > 0) & y_() * z_() > 0 -> z_() > 0".asFormula,QE,namespace).fact
  lazy val dbxEqRw: ProvableSig = remember("(p() & y_() > 0) & y_() * z_() = 0 -> z_() = 0".asFormula,QE,namespace).fact
  lazy val dbxNeqRw: ProvableSig = remember("(p() & y_() > 0) & y_() * z_() != 0 -> z_() != 0".asFormula,QE,namespace).fact

  private lazy val dbxEqOne: ProvableSig = ProvableSig.proveArithmetic(BigDecimalQETool, "1*1^2=1".asFormula)
  private val zero = Number(0)
  private val one = Number(1)
  private val two = Number(2)

  // was "dbx"
  def dgDbx(qco: Term): DependentPositionWithAppliedInputTactic = inputanon ((pos: Position, seq:Sequent) => {
    require(pos.isSucc && pos.isTopLevel, "dbx only at top-level succedent")

    val (system,property) = seq.sub(pos) match {
      case Some (Box (ODESystem (system, _), property) ) => (system,property)
      case Some(e) => throw new TacticInapplicableFailure("dbx only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }

    /** The argument works for any comparison operator */
    val (p, pop) = property match {
      case bop: ComparisonFormula if bop.right.isInstanceOf[Number] && bop.right.asInstanceOf[Number].value == 0 =>
        (bop.left,bop)
      case e => throw new TacticInapplicableFailure(s"Not sure what to do with shape ${e.prettyString}, dgDbx requires 0 on RHS")
    }

    val dbxRw = pop match {
      case LessEqual(_,_) => dbxLeqRw
      case GreaterEqual(_,_) => dbxGeqRw
      case Less(_,_) => dbxLtRw
      case Greater(_,_) => dbxGtRw
      case Equal(_,_) => dbxEqRw
      case NotEqual(_,_) => dbxNeqRw
      case _ =>  ??? // caught by exception in previous case match
    }

    val isOpen = property match {
      case  _: Greater => true
      case _: Less => true
      case _ => false
    }

    //Skip ghosts if input cofactor was just 0
    //Could also do more triviality checks like -0, 0+0 etc.
    if (qco == zero) {
      //println("dgDbx automatically used dI for trivial cofactor")
      if(isOpen) openDiffInd(pos) else diffInd('full)(pos)
    }
    else {
      /** The ghost variable */
      val gvy = TacticHelper.freshNamedSymbol("dbxy_".asVariable,seq)

      /** Another ghost variable */
      val gvz = TacticHelper.freshNamedSymbol("dbxz_".asVariable,seq)

      //Construct the diff ghost y' = -qy
      val dey = AtomicODE(DifferentialSymbol(gvy), Times(Neg(qco), gvy))
      //Diff ghost z' = qz/2
      val dez = AtomicODE(DifferentialSymbol(gvz), Times(Divide(qco, two), gvz))

      //Postcond:
      //For equalities, != 0 works too, but the > 0 works for >=, > as well
      val gtz = Greater(gvy, zero)
      val pcy = pop.reapply(Times(gvy, p), zero)
      val pcz = Equal(Times(gvy, Power(gvz, two)), one)

      DebuggingTactics.debug("Darboux postcond " + pcy.toString + " " + pcz.toString) &
        dG(dey, None)(pos) & //Introduce the dbx ghost
        existsR(one)(pos) & //Anything works here, as long as it is > 0, 1 is convenient
        diffCut(gtz)(pos) < (
          diffCut(pcy)(pos) <(
            diffWeakenG(pos) & byUS(dbxRw)
            ,
            if (isOpen) openDiffInd(pos) else diffInd('full)(pos)
          )
          ,
          //@note does not need Dconstify since postcondition is dbxy_>0 and ODE has dbxy_'
          DifferentialTactics.dG(dez, Some(pcz))(pos) & //Introduce the dbx ghost
            existsR(one)(pos) & //The sqrt inverse of y, 1 is convenient
            diffInd('diffInd)(pos) // Closes z > 0 invariant with another diff ghost
              <(
              hideL('Llast) & exhaustiveEqL2R(hide=true)('Llast)*2 & useAt(dbxEqOne)(pos) & closeT,
              cohideR('Rlast) & SaturateTactic(Dassignb(1)) & byUS(dbxCond)
            )
        )
    }
  })

  /**
    * This tactic is EXPERIMENTAL: it requires the use of max in a ghost
    * Proves a strict barrier certificate property
    * p >~ 0 -> [ {ODE & Q} ] p >~ 0
    * where Q & p = 0 -> p' > 0
    * works for both > and >= (and <, <=)
    * Soundness note: this uses a ghost that is not smooth
    */
  private val maxF = Function("max", None, Tuple(Real, Real), Real, interpreted=true)
  private val minF = Function("min", None, Tuple(Real, Real), Real, interpreted=true)

  private lazy val barrierCond: ProvableSig = remember("max(f_()*f_(),g_()) > 0 <-> f_()=0 -> g_()>0".asFormula,QE,namespace).fact
  private lazy val barrierCond2: ProvableSig = remember("h_() = k_() -> max(g_()*g_(),h_()) > 0 -> f_() > 0 ->  ((-(g_()*h_())/max(g_()*g_(),h_())) * f_() + 0) * g_() + f_() * k_() >=0".asFormula,QE,namespace).fact

  // was named "barrieraux"
  private def dgBarrierAux : DependentPositionTactic = anon ((pos: Position, seq:Sequent) => {
    require(pos.isSucc && pos.isTopLevel, "barrier only at top-level succedent")

    val (system,dom,post) = seq.sub(pos) match {
      case Some (Box (ODESystem (system, dom), property) ) => (system,dom,property)
      case Some(e) => throw new TacticInapplicableFailure("barrier only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }

    val (property, propt)= try {
      ineqNormalize(post)
    } catch {
      case ex: IllegalArgumentException => throw new TacticInapplicableFailure("Unable to normalize postcondition", ex)
    }

    val starter = propt match {
      case None => skip
      case Some(pr) => useAt(pr)(pos ++ PosInExpr(1::Nil))
    }

    //p>=0 or p>0
    val cf = property.asInstanceOf[ComparisonFormula]

    val dbxRw = cf match {
      case LessEqual(_,_) => dbxLeqRw
      case GreaterEqual(_,_) => dbxGeqRw
      case Less(_,_) => dbxLtRw
      case Greater(_,_) => dbxGtRw
      case Equal(_,_) => dbxEqRw
      case NotEqual(_,_) => dbxNeqRw
      case _ => ???
    }

    val barrier = cf.left

    val lie = DifferentialHelper.simplifiedLieDerivative(system, barrier, ToolProvider.simplifierTool())

    //The special max term
    val barrierAlg = FuncOf(maxF, Pair(Times(barrier, barrier), lie))
    val barrierFml = Greater(barrierAlg, zero)
    //The cofactor
    val cofactor = Divide(Times(barrier, lie), barrierAlg)

    // First cut in the barrier property, then use dgdbx on it
    // Barrier condition is checked first to make it fail faster
    val pre = diffCut(barrierFml)(pos) < (
        skip, /* diffWeakenG faster but loses assumptions*/
        //todo: Not sure why dW sometimes fails here
        (dW(pos) & useAt(barrierCond)(1) | diffWeakenG(pos) & useAt(barrierCond)(1, 1 :: Nil)) & timeoutQE & done
    ) &
    starter

    // Same as dgDbx but bypasses extra checks since we already know
    /** The ghost variable */
    val gvy = TacticHelper.freshNamedSymbol("dbxy_".asVariable,seq)

    /** Another ghost variable */
    val gvz = TacticHelper.freshNamedSymbol("dbxz_".asVariable,seq)

    //Postcond:
    val gtz = Greater(gvy, zero)
    val pcy = cf.reapply(Times(gvy, barrier), zero)
    val pcz = Equal(Times(gvy, Power(gvz, two)), one)

    //Construct the diff ghost y' = -qy
    val dey = AtomicODE(DifferentialSymbol(gvy), Times(Neg(cofactor), gvy))

    def inspectAndCut : DependentTactic = anon ((seq:Sequent) => {
      val k = seq.succ(0)(PosInExpr(1::1::0::1::1::Nil)).asInstanceOf[Term]
      cutR(Equal(lie,k))(1)
    })

    // pos = -1
    def hideUntil : DependentPositionTactic = anon ((pos:Position,seq:Sequent) => {
      seq.sub(pos) match {
        case Some(And(l,Greater(_,_))) => andL(pos) & hideL(-1)
        case _ => andL(pos) & hideL(-2) & hideUntil(pos)
      }
    })

    //Diff ghost z' = qz/2
    val dez = AtomicODE(DifferentialSymbol(gvz), Times(Divide(cofactor, two), gvz))

      pre &
      DifferentialTactics.dG(dey, None)(pos) & //Introduce the dbx ghost
      existsR(one)(pos) & //Anything works here, as long as it is > 0, 1 is convenient
      diffCut(gtz)(pos) <(
        diffCut(pcy)(pos) <(
          diffWeakenG(pos) & byUS(dbxRw),
          diffInd('diffInd)(pos) <(
            hideL('Llast) & QE,
            cohideOnlyL('Llast) & andL(-1) &
              cohideOnlyR('Rlast) & SaturateTactic(Dassignb(1)) &
              implyRi()(AntePos(1),SuccPos(0)) &
              hideUntil(-1) &
            //This implyRi is specific to the shape of the above diffInd, diffCut dG steps
            implyRi()(AntePos(0),SuccPos(0)) & inspectAndCut <(
              QE,
              byUS(barrierCond2)
            )
          )
        )
        ,
        DifferentialTactics.dG(dez, Some(pcz))(pos) & //Introduce the dbx ghost
          existsR(one)(pos) & //The sqrt inverse of y, 1 is convenient
          // Closes z > 0 invariant with another diff ghost
          diffInd('diffInd)(pos) <(
            hideL('Llast) & exhaustiveEqL2R(hide=true)('Llast)*2 & useAt(dbxEqOne)(pos) & closeT,
            cohideR('Rlast) & SaturateTactic(Dassignb(1)) & byUS(dbxCond) & done
          )
      )
  })

  @Tactic(names="Strict Barrier Certificate",
    codeName="barrier", // todo: rename the tactic directly
    premises="Γ |- p≳0 ;; Q ∧ p=0 |- p'>0",
    conclusion="Γ |- [x'=f(x) & Q] p≳0, Δ",
    displayLevel="browse")
  val dgBarrier: DependentPositionTactic = anon ((pos: Position, seq:Sequent) => {
    Dconstify(dgBarrierAux(pos))(pos)
  })

  /** Find Q|- p' = q p + r, and proves |- Q -> r~0 with appropriate
    * sign condition on r as specified by "property"
    * In addition, if the "property" was open, then also assume it in Q
    */
  private [btactics] def findDbx(ode: DifferentialProgram, dom: Formula,
                                 property: ComparisonFormula, strict:Boolean=true): (ProvableSig,Term,Term) = {

    val p = property.left
    val lie = DifferentialHelper.simplifiedLieDerivative(ode, p, ToolProvider.simplifierTool())
    // p' = q p + r
    val (q,r) = domQuoRem(lie,p,dom)

    //The sign of the remainder for a Darboux argument
    //e.g., tests r >= 0 for p'>=gp (Darboux inequality)
    val pr = try {
      property match {
        case GreaterEqual(_, _) => {
          proveBy(Imply(dom, GreaterEqual(r, zero)), timeoutQE)
        }
        case Greater(_, _) => proveBy(Imply(And(dom, property), GreaterEqual(r, zero)), timeoutQE)
        case LessEqual(_, _) => proveBy(Imply(dom, LessEqual(r, zero)), timeoutQE)
        case Less(_, _) => proveBy(Imply(And(dom, property), LessEqual(r, zero)), timeoutQE)
        case Equal(_, _) => proveBy(Imply(dom, Equal(r, zero)), timeoutQE)
        //todo: is there a special case of open DI that would work for disequalities?
        case NotEqual(_, _) => proveBy(Imply(dom, Equal(r, zero)), timeoutQE)
        case _ => throw new TacticInapplicableFailure(s"Darboux only on atomic >,>=,<,<=,!=,= postconditions")
      }
    }
    catch {
      //todo: Instead of eliminating quantifiers, Z3 will throw an exception that isn't caught by ?(timeoutQE)
      //This is a workaround
      case e : BelleThrowable if e.getCause.isInstanceOf[SMTQeException] =>  proveBy(False, skip)
    }

    if(pr.isProved)
      return (pr,q,r)
    if(q != zero) {
      // Fall-back check if straightforward DI would work
      // This is needed, because one can e.g. get p'>=0 without having r>=0 when domain constraints are possible
      // todo: is it possible to improve the Darboux (in)equality generation heuristic to automatically cover this case?
      val pr = try {
        property match {
          case GreaterEqual(_, _) => proveBy(Imply(dom, GreaterEqual(lie, zero)), timeoutQE)
          case Greater(_, _) => proveBy(Imply(And(dom, property), GreaterEqual(lie, zero)), timeoutQE)
          case LessEqual(_, _) => proveBy(Imply(dom, LessEqual(lie, zero)), timeoutQE)
          case Less(_, _) => proveBy(Imply(And(dom, property), LessEqual(lie, zero)), timeoutQE)
          case Equal(_, _) => proveBy(Imply(dom, Equal(lie, zero)), timeoutQE)
          //todo: is there a special case of open DI that would work for disequalities?
          case NotEqual(_, _) => proveBy(Imply(dom, Equal(lie, zero)), timeoutQE)
          case _ => throw new TacticInapplicableFailure(s"Darboux only on atomic >,>=,<,<=,!=,= postconditions")
        }
      }
      catch {
        //todo: Instead of eliminating quantifiers, Z3 will throw an exception that isn't caught by ?(timeoutQE)
        //This is a workaround
        case e : BelleThrowable if e.getCause.isInstanceOf[SMTQeException] => proveBy(False, skip)
      }
      if(pr.isProved)
        return (pr,zero,lie)
    }

    if(strict)
      throw new ProofSearchFailure("Automatic darboux failed -- poly :"+p+" lie: "+lie+" cofactor: "+q+" rem: "+r+" unable to prove: "+pr.conclusion)

    (pr,q,r)
  }

  // Normalises to p = 0 then attempts to automatically guess the darboux cofactor
  // was named "dbx"
  def dgDbxAuto: DependentPositionTactic = anon ((pos: Position, seq:Sequent) => {
    require(pos.isSucc && pos.isTopLevel, "dgDbxAuto only at top-level succedent")

    val (system,dom,post) = seq.sub(pos) match {
      case Some (Box (ODESystem (system, dom), property) ) => (system,dom,property)
      case Some(e) => throw new TacticInapplicableFailure("dbx auto only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }

    val (property,propt) = atomNormalize(post)

    val starter = propt match {
      case None => skip
      case Some(pr) => useAt(pr)(pos ++ PosInExpr(1::Nil))
    }

    //normalized to have p on LHS
    //todo: utilize pr which proves the necessary sign requirement for denRemReq
    val (pr,cofactor,rem) = try {
      findDbx(system,dom,property.asInstanceOf[ComparisonFormula])
    } catch {
      case ex: ProofSearchFailure => throw new TacticInapplicableFailure("dbx auto unable to automatically determine Darboux cofactors.", ex)
    }

    starter & dgDbx(cofactor)(pos)
  })

  @Tactic(names="Darboux (in)equalities",
    premises="Γ |- p≳0 ;; Q |- p' ≳ g p",
    conclusion="Γ |- [x'=f(x) & Q]p≳0, Δ",
    inputs="g:option[term]",
    displayLevel="browse")
  def dbx(g : Option[Term]) : DependentPositionWithAppliedInputTactic = inputanon ({ pos: Position =>
    g match {
      case None => dgDbxAuto(pos)
      case Some(cof) => dgDbx(cof)(pos)
    }
  })

  /** @see [[TactixLibrary.DGauto]]
    * @author Andre Platzer */
  def DGauto: DependentPositionTactic = anon((pos:Position,seq:Sequent) => {
    if (ToolProvider.algebraTool().isEmpty) throw new ProverSetupException("DGAuto requires a AlgebraTool, but got None")
    /** a-b with some simplifications */
    def minus(a: Term, b: Term): Term = b match {
      case Number(n) if n == 0 => a
      case _ => a match {
        case Number(n) if n == 0 => Neg(b)
        case _ => Minus(a, b)
      }
    }
    val (quantity: Term, ode: DifferentialProgram) = seq.sub(pos) match {
      case Some(Box(ODESystem(o, _), Greater(a, b))) => (minus(a, b), o)
      case Some(Box(ODESystem(o, _), Less(a, b))) => (minus(b, a), o)
      case Some(e) => throw new TacticInapplicableFailure("DGauto does not support argument shape: " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }
    //@todo find a ghost that's not in ode
    val ghost: Variable = Variable("y_")
    require(!StaticSemantics.vars(ode).contains(ghost), "fresh ghost " + ghost + " in " + ode)
    // [x':=f(x)](quantity)'
    val lie = DifferentialHelper.lieDerivative(ode, quantity)

    lazy val constrGGroebner: Term = {
      val groebnerBasis: List[Term] = ToolProvider.algebraTool().getOrElse(throw new ProverSetupException("DGAuto requires an AlgebraTool, but got None")).groebnerBasis(
        quantity :: Nil)
      ToolProvider.algebraTool().getOrElse(throw new ProverSetupException("DGAuto requires an AlgebraTool, but got None")).polynomialReduce(
        lie match {
          case Minus(Number(n), l) if n == 0 => l //@note avoid negated ghost from (f()-x)'
          case _ => lie
        },
        groebnerBasis.map(Times(Number(-2), _))
      )._1.head
    }

    val odeBoundVars = StaticSemantics.boundVars(ode).symbols[NamedSymbol].toList.filter(_.isInstanceOf[BaseVariable]).sorted.map(_.asInstanceOf[BaseVariable])
    val constrG: Term = ToolProvider.algebraTool().getOrElse(throw new ProverSetupException("DGAuto requires an AlgebraTool, but got None")).quotientRemainder(
      lie, Times(Number(-2), quantity), odeBoundVars.headOption.getOrElse(Variable("x")))._1

    // Formula that must be valid: quantity <-> \exists ghost. quantity * ghost^2 > 0
    // Ghosted-in differential equation: ghost' = constrG*ghost + 0
    def dg(g: Term): BelleExpr = {
      val de = AtomicODE(DifferentialSymbol(ghost), Plus(Times(g, ghost), Number(0)))
      val p = Greater(Times(quantity, Power(ghost, Number(2))), Number(0))
      DebuggingTactics.debug(s"DGauto: trying $de with $p") &
      dG(de,Some(p))(pos) & diffInd()(pos ++ PosInExpr(0::Nil)) & QE & done
    }

    // try guessing first, groebner basis if guessing fails
    dg(constrG) | TacticFactory.anon((seq: Sequent) => dg(constrGGroebner))
  })

  /** Search-and-rescue style DGauto.
    * @author Andre Platzer
    */
  // was named "DGauto"
  def DGautoSandR: DependentPositionTactic = anon ((pos:Position,seq:Sequent) => {
    if (!ToolProvider.solverTool().isDefined) throw new ProverSetupException("DGAuto requires a SolutionTool, but got None")
    /** a-b with some simplifications */
    def minus(a: Term, b: Term): Term = b match {
      case Number(n) if n==0 => a
      case _ => a match {
        case Number(n) if n==0 => Neg(b)
        case _ => Minus(a,b)
      }
    }
    val (quantity: Term, ode: DifferentialProgram) = seq.sub(pos) match {
      case Some(Box(ODESystem(ode, _), Greater(a, b))) => (minus(a,b), ode)
      case Some(Box(ODESystem(ode, _), Less(a, b))) => (minus(b,a), ode)
      case Some(e) => throw new TacticInapplicableFailure("DGauto does not support argument shape: " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }
    //@todo find a ghost that's not in ode
    val ghost: Variable = Variable("y_")
    require(!StaticSemantics.vars(ode).contains(ghost), "fresh ghost " + ghost + " in " + ode)
    val spooky: Term = if (false) //@todo ultimate substitution won't work if it ain't true. But intermediate semantic renaming won't work if it's false.
      UnitFunctional("jj",Except(ghost::Nil),Real)
    else
      FuncOf(Function("jj",None,Unit,Real),Nothing) //Variable("jj")
    //@todo should allocate space maybe or already actually by var in this lambda
    var constructedGhost: Option[Term] = None
    // proper search & rescue tactic
    val SandR: BelleExpr = LetInspect(
      spooky,
      (pr:ProvableSig) => {
        assume(pr.subgoals.length==1, "exactly one subgoal from DA induction step expected")
        logger.debug("Instantiate::\n" + pr)
        // induction step condition \forall x \forall ghost condition>=0
        val condition = FormulaTools.kernel(pr.subgoals.head.succ.head) match {
          case Imply(domain, GreaterEqual(condition, Number(n))) if n==0 => condition
          case GreaterEqual(condition, Number(n)) if n==0 => condition
          case _ => throw new AssertionError("DGauto: Unexpected shape " + pr)
        }
        //@todo a witness of Reduce of >=0 would suffice
        logger.debug("Solve[" + condition + "==0" + "," + spooky + "]")
        ToolProvider.solverTool().getOrElse(throw new ProverSetupException("DGAuto requires a SolutionTool, but got None")).solve(Equal(condition, Number(0)), spooky::Nil) match {
          case Some(Equal(l,r)) if l==spooky => logger.debug("Need ghost " + ghost + "'=(" + r + ")*" + ghost + " for " + quantity)
            constructedGhost = Some(r)
            r
          case None => logger.debug("Solve[" + condition + "==0" + "," + spooky + "]")
            throw new TacticInapplicableFailure("DGauto could not solve conditions: " + condition + ">=0")
          case Some(stuff) => logger.debug("Solve[" + condition + "==0" + "," + spooky + "]")
            throw new TacticInapplicableFailure("DGauto got unexpected solution format: " + condition + ">=0\n" + stuff)
        }
      }
      ,
      dG(AtomicODE(DifferentialSymbol(ghost), Plus(Times(spooky, ghost), Number(0))),
        Some(Greater(Times(quantity, Power(ghost,Number(2))), Number(0)))
      )(pos) & diffInd()(pos ++ PosInExpr(0::Nil))
    ) & QE & done
    // fallback rescue tactic if proper misbehaves
    val fallback: DependentPositionTactic = anon ((pos:Position,seq:Sequent) => {
      logger.debug("DGauto falling back on ghost " + ghost + "'=(" + constructedGhost + ")*" + ghost)
      // terrible hack that accesses constructGhost after LetInspect was almost successful except for the sadly failing usubst in the end.
      dG(AtomicODE(DifferentialSymbol(ghost), Plus(Times(constructedGhost.getOrElse(throw new TacticInapplicableFailure("DGauto construction was unsuccessful in constructing a ghost")), ghost), Number(0))),
        Some(Greater(Times(quantity, Power(ghost, Number(2))), Number(0)))
      )(pos) <(
        QE & done,
        //@todo could optimize for RCF cache when doing the same decomposition as during SandR
        //diffInd()(pos ++ PosInExpr(1::Nil)) & QE
        implyR(pos) & diffInd()(pos) & QE & done
        )
    })
    SandR | fallback(pos)
  })

  /** Pieces together some ODE invariance tactics into a prover for ODE invariance:
    *
    * G |- P   P|-[x'=f(x)&Q]P
    * ---
    * G |- [x'=f(x)&Q]P
    *
    * @param tryHard configures how hard the tactic tries to prove invariance in particular
    *                use tryHard = true when speed is secondary & certain that P is invariant
    *                use tryHard = false when speed is of interest e.g., within automated invariant search
    */
  def odeInvariant(tryHard: Boolean = false, useDw: Boolean = true): DependentPositionTactic = anon ((pos:Position) => {
    require(pos.isSucc && pos.isTopLevel, "ODE invariant only applicable in top-level succedent")
    //@note dW does not need algebra tool
    //require(ToolProvider.algebraTool().isDefined,"ODE invariance tactic needs an algebra tool (and Mathematica)")

    val invTactic =
      if(tryHard)
      {
        ODEInvariance.sAIclosedPlus(bound = 3)(pos) |
        //todo: duplication currently necessary between sAIclosedPlus and sAIclosed due to unresolved Mathematica issues
        ODEInvariance.sAIclosed(pos) |
        ?(DifferentialTactics.dCClosure(cutInterior=true)(pos) <(timeoutQE & done,skip)) & //strengthen to the closure if applicable
        ODEInvariance.sAIRankOne(doReorder = true, skipClosed = false)(pos)
      }
      else {
        ODEInvariance.sAIclosedPlus(bound = 1)(pos) |
        // ?(DifferentialTactics.dCClosure(cutInterior=true)(pos) <(timeoutQE & done,skip)) & //strengthen to the closure if applicable
        ODEInvariance.sAIRankOne(doReorder = false, skipClosed = true)(pos)
      }

    val diffWeaken =
      if(tryHard) DifferentialTactics.diffWeakenPlus(pos) else DifferentialTactics.diffWeakenG(pos)

    //Add constant assumptions to domain constraint
    SaturateTactic(andL('L)) & //Safe because pos is guaranteed to be in the succedent
    DifferentialTactics.DconstV(pos) &
    //Naive simplification of postcondition with domain constraint
    DifferentialTactics.domSimplify(pos) &
    DebuggingTactics.debug("odeInvariant close") &
    (
      if (useDw) {
        (diffWeaken & timeoutQE & done) |
          invTactic |
          DebuggingTactics.debug("odeInvariant failed to prove postcondition invariant for ODE. Try using a differential cut to refine the domain constraint first.")
      } else {
        invTactic |
          DebuggingTactics.debug("odeInvariant failed to prove postcondition invariant for ODE. Try using a differential cut to refine the domain constraint first.")
      }
    )
  })

  /** Same as odeInvariant but reports a completeness error when it detects that the postcondition should be invariant
    * but currently unprovable
    */
  def odeInvariantComplete: DependentPositionTactic = anon ((pos:Position,seq:Sequent) => {
    require(pos.isSucc && pos.isTopLevel, "ODE invariant only applicable in top-level succedent")

    val (ode,post) = seq.sub(pos) match {
      case Some(Box(ode:ODESystem,post)) => (ode,post)
      case Some(e) => throw new TacticInapplicableFailure("ODE invariant only applicable to box ODEs, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }

    odeInvariant(tryHard=true)(pos) |
    DebuggingTactics.assert(s =>
      if (s != seq) true // should never happen
      else {
        ToolProvider.invGenTool() match {
          case None => throw new UnsupportedTacticFeature("odeInvC was unable to prove postcondition invariant for ODE nor disprove its invariance." +
            " This may be a completeness bug in the implementation." +
            " Please submit a bug report if you are sure that "+post+" is an ODE invariant for "+ode)
          case Some(tool) =>
            val(nec,_) = tool.genODECond(ode, s.ante, post)
            val pr = proveBy(nec.tail.foldLeft(nec.head)(And),?(timeoutQE))
            if(pr.isProved)
              throw new UnsupportedTacticFeature("The ODE postcondition "+post+" is invariant but odeInvC could not prove it." +
                " This is a completeness bug in the implementation. Please submit a bug report.")
            else
              throw new UnsupportedTacticFeature("odeInvC was unable to prove postcondition invariant for ODE nor disprove its invariance." +
                " This may be a completeness bug in the implementation." +
                " Please submit a bug report if you are sure that "+post+" is an ODE invariant for "+ode)
        }
      }
      , "", new TacticInapplicableFailure(_))
  })

  // Asks Pegasus invariant generator for an invariant (DC chain)
  // Try hard to prove G|-[x'=f(x)&Q]P by an invariance argument with the chain only (NO SOLVE)
  // was named "odeInvariant"
  lazy val odeInvariantAuto : DependentPositionTactic = anon ((pos:Position, seq: Sequent) => {
    require(pos.isTopLevel && pos.isSucc, "ODE invariant (with Pegasus) only applicable in top-level succedent")
    require(ToolProvider.algebraTool().isDefined,"ODE invariance tactic needs an algebra tool (and Mathematica)")

    SaturateTactic(andL('L)) & //Safe because pos is guaranteed to be in the succedent
    DifferentialTactics.DconstV(pos) & odeInvariantAutoBody(pos)
  })

  private def odeInvariantAutoBody: DependentPositionTactic = anon ((pos:Position,seq:Sequent) => {
    val invs = InvariantGenerator.pegasusInvariants(seq,pos).toList
    //Empty list = failed to generate an invariant
    //True ~ no DCs needed
    //Else, DC chain
    // Assume that Pegasus hands us back a diffcut chain
    invs.headOption match {
      case None => throw new BelleNoProgress(s"Pegasus failed to generate an invariant")
      case Some((True, _)) => diffWeakenG(pos) & timeoutQE & done
      case _ =>
        invs.foldRight(diffWeakenG(pos) & timeoutQE & done)( (fml,tac) =>
          //DebuggingTactics.print("DC chain: "+fml) &
          DC(fml._1)(pos) <(tac,
            (
            //note: repeated dW&QE not needed if Pegasus reports a correct dC chain
            //(DifferentialTactics.diffWeakenG(pos) & QE & done) |
            ODEInvariance.sAIclosedPlus(bound=1)(pos) |
            ODEInvariance.sAIRankOne(doReorder=false,skipClosed = true)(pos)) & done)
        )
    }
  })

  // implementation helpers

  /** Computes quotient remainder resulting from (RATIONAL) polynomial division wrt equalities in domain
    * @param poly polynomial to divide
    * @param div divisor
    * @param dom domain constraint
    * @return (q,r) where Q |- poly = q*div + r , q,r are polynomials
    */
  def domQuoRem(poly: Term, div: Term, dom: Formula): (Term,Term) = {
    if (ToolProvider.algebraTool().isEmpty) {
      throw new ProverSetupException(s"duoQuoRem requires a AlgebraTool, but got None")
      // val polynorm = PolynomialArith.normalise(poly,true)._1
      // val divnorm = PolynomialArith.normalise(div,true)._1
    }
    else {
      val algTool = ToolProvider.algebraTool().get
      val gb = algTool.groebnerBasis(domainEqualities(dom))
      val quo = algTool.polynomialReduce(poly, div :: gb)
      // quo._1.head is the cofactor of div (q)
      // quo._2 is the remainder (r)

      (quo._1.head,quo._2)
      //Older support for rational functions
      //val (g, q) = stripDenom(quo._1.head)
      //if ((FormulaTools.singularities(g) ++ FormulaTools.singularities(q)).isEmpty) (g, q, quo._2)
      //else (Number(0), Number(1), poly)
    }
  }

  //Keeps equalities in domain constraint
  //dropFuncs drops all equalities involving (non-constant) function symbols
  private[btactics] def domainEqualities(f:Formula, dropFuncs:Boolean = true) : List[Term] = {
    flattenConjunctions(f).flatMap{
      case Equal(l,r) => {
        val sig = StaticSemantics.signature(Equal(l,r))
        if(dropFuncs && !sig.exists(e => e.isInstanceOf[Function] && e.asInstanceOf[Function].sort != Unit))
          Some(Minus(l, r))
        else None
      }
      case _ => None
    }
  }

  /** Indicates whether there is an ODE at the indicated position of a sequent */
  val isODE: (Sequent,Position)=>Boolean = (sequent,pos) => {
    sequent.sub(pos) match {
      case Some(Box(_: ODESystem, _))     => true
      case Some(Diamond(_: ODESystem, _)) => true
      case Some(e) => false
      case None => throw new IllegalArgumentException("ill-positioned " + pos + " in " + sequent)
    }
  }

  /** Indicates whether there is a proper ODE System at the indicated position of a sequent with >=2 ODEs */
  val isODESystem: (Sequent,Position)=>Boolean = (sequent,pos) => {
    sequent.sub(pos) match {
      case Some(Box(ODESystem(_:DifferentialProduct,_), _))     => true
      case Some(Diamond(ODESystem(_:DifferentialProduct,_), _)) => true
      case Some(e) => false
      case None => throw new IllegalArgumentException("ill-positioned " + pos + " in " + sequent)
    }
  }

  /** Computes the dimension of ODE at indicated position of a sequent */
  private[btactics] val getODEDim: (Sequent,Position)=>Int = (sequent,pos) => {
    def odeDim(ode: ODESystem): Int = StaticSemantics.boundVars(ode).symbols.count(_.isInstanceOf[DifferentialSymbol])
    sequent.sub(pos) match {
      case Some(Box(ode: ODESystem, _))     => odeDim(ode)
      case Some(Diamond(ode: ODESystem, _)) => odeDim(ode)
      case Some(e) => throw new IllegalArgumentException("no ODE at position " + pos + " in " + sequent + "\nFound: " + e)
      case None => throw new IllegalArgumentException("ill-positioned " + pos + " in " + sequent)
    }
  }

  /** Whether the ODE at indicated position of a sequent has a nontrivial domain */
  val hasODEDomain: (Sequent,Position)=>Boolean = (sequent,pos) => {
    sequent.sub(pos) match {
      case Some(Box(ode: ODESystem, _))     => ode.constraint != True
      case Some(Diamond(ode: ODESystem, _)) => ode.constraint != True
      case Some(e) => throw new IllegalArgumentException("no ODE at position " + pos + " in " + sequent + "\nFound: " + e)
      case None => throw new IllegalArgumentException("ill-positioned " + pos + " in " + sequent)
    }
  }

  /** Flattens a formula to a list of its top-level conjunctions */
  def flattenConjunctions(f: Formula): List[Formula] = {
    var result: List[Formula] = Nil
    ExpressionTraversal.traverse(new ExpressionTraversal.ExpressionTraversalFunction {
      override def preF(p: PosInExpr, f: Formula): Either[Option[ExpressionTraversal.StopTraversal], Formula] = f match {
        case And(l, r) => result = result ++ flattenConjunctions(l) ++ flattenConjunctions(r); Left(Some(ExpressionTraversal.stop))
        case a => result = result :+ a; Left(Some(ExpressionTraversal.stop))
      }
    }, f)
    result
  }

  // TODO: these Lemmas are just the symmetric versions of some DerivedAxioms.
  // Using PosInExpr(1::Nil) as key in chaseCustom does not seem to work.
  private lazy val minPosAnd = remember("min(f_(), g_())>0<->f_()>0 & g_()>0".asFormula, QE & done)
  private lazy val minNonnegAnd = remember("min(f_(), g_())>=0<->f_()>=0 & g_()>=0".asFormula, QE & done)
  private lazy val maxPosOr = remember("max(f_(), g_())>0<->f_()>0 | g_()>0".asFormula, QE & done)
  private lazy val maxNonnegOr = remember("max(f_(), g_())>=0<->f_()>=0 | g_()>=0".asFormula, QE & done)
  private lazy val minusPos = remember("f_()-g_()>0 <-> f_()>g_()".asFormula, QE & done)
  private lazy val minusNonneg = remember("f_()-g_()>=0 <-> f_()>=g_()".asFormula, QE & done)
  /** chases min/max Less/LessEqual 0 to conjunctions and disjunctions */
  val chaseMinMaxInequalities : DependentPositionTactic = chaseCustom({
    case Greater(FuncOf(m, _), Number(n)) if m == minF =>
      (minPosAnd.fact, PosInExpr(0::Nil), PosInExpr(0::Nil)::PosInExpr(1::Nil)::Nil)::Nil
    case GreaterEqual(FuncOf(m, _), Number(n)) if m == minF =>
      (minNonnegAnd.fact, PosInExpr(0::Nil), PosInExpr(0::Nil)::PosInExpr(1::Nil)::Nil)::Nil
    case Greater(FuncOf(m, _), Number(n)) if m == maxF =>
      (maxPosOr.fact, PosInExpr(0::Nil), PosInExpr(0::Nil)::PosInExpr(1::Nil)::Nil)::Nil
    case GreaterEqual(FuncOf(m, _), Number(n)) if m == maxF =>
      (maxNonnegOr.fact, PosInExpr(0::Nil), PosInExpr(0::Nil)::PosInExpr(1::Nil)::Nil)::Nil
    case _ => Nil
  })

  private def interiorImplication: DependentTactic = anon { (seq: Sequent) =>
    require (seq.succ.length == 1)
    require (seq.ante.length == 1)
    (seq.ante(0), seq.succ(0)) match {
      case (And(p, q), And(r, s)) =>
        andL(-1) & andR(1) & Idioms.<(
          hideL(-2) & interiorImplication,
          hideL(-1) & interiorImplication
        )
      case (Or(p, q), Or(r, s)) =>
        orR(1) & orL(-1) & Idioms.<(
          hideR(2) & interiorImplication,
          hideR(1) & interiorImplication
        )
      case (Less(a, b), LessEqual(c, d)) if a == c && b == d => useAt(Ax.lessEqual)(1) & orR(1) & id
      case (Greater(a, b), GreaterEqual(c, d)) if a == c && b == d => useAt(Ax.greaterEqual)(1) & orR(1) & id
      case (False, _) => closeF
      case (x, y) if x == y => id
      case _ =>
        throw new TacticInapplicableFailure("strengthenInequalities expected ante and succ of same shape, but got " + seq)
    }
  }

  /**
    * Strengthens the postcondition to its interior and cuts in its closure
    * (provided the closure holds initially).
    *
    * G |- [{ode&p&closure(q)}]interior(q)           G |- interior(q) (or closure(q) if cutInterior=false)
    * ----------------------------------------------------------------dCClosure
    * G |- [{ode&p}]q
    *
    * Cuts interior(q) true initially by default (but this can be set to closure(q) instead)
    * interior(q) and closure(q) are wrt. to the negation normal form (NNF) of q
    * @see [[FormulaTools.interior]]
    * @see [[FormulaTools.closure]]
    *
    */
  def dCClosure(cutInterior:Boolean): DependentPositionTactic = anon ((pos:Position,seq: Sequent) => {
    require(pos.isTopLevel && pos.isSucc, "dCClosure expects to be called on top-level succedent")

    val (ode,p_fml,post) = seq.sub(pos) match {
      case Some(Box(sys:ODESystem,p)) => (sys.ode,sys.constraint,p)
      case Some(e) => throw new TacticInapplicableFailure("dCClosure only applicable to box ODEs of shape [{ode&p}]q, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }

    val (q_fml, propt) = try {
      semiAlgNormalize(post)
    } catch {
      case ex: IllegalArgumentException => throw new TacticInapplicableFailure("Unable to normalize postcondition to semi-algebraic set", ex)
    }

    /* Apply the semialg normalization step */
    val starter = propt.map(pr => useAt(pr)(pos ++ PosInExpr(1 :: Nil))).getOrElse(skip)

    val interior = FormulaTools.interior(q_fml)
    val closure = FormulaTools.closure(q_fml)

    val (mm_fmlGt, proptGt) = try {
      maxMinGtNormalize(interior)
    } catch {
      case ex: IllegalArgumentException => throw new TacticInapplicableFailure("Unable to normalize interior", ex)
    }
    val (mm_fmlGe, proptGe) = try {
      maxMinGeqNormalize(closure)
    } catch {
      case ex: IllegalArgumentException => throw new TacticInapplicableFailure("Unable to normalize closure", ex)
    }

    //NOTE: mm_fmlGt should be identical to mm_fmlGe except with > instead of >=

    /* Apply/Undo the max-min normalization steps */

    val (maxminGt, backGt) = proptGt match {
      case None => (skip, skip)
      case Some(pr) => (useAt(pr)(pos ++ PosInExpr(1 :: Nil)), useAt(pr, PosInExpr(1 :: Nil))(pos ++ PosInExpr(1 :: Nil)))
    }

    val (backGe1, backGe2) = proptGe match {
      case None => (skip, skip)
      case Some(pr) => (useAt(pr, PosInExpr(1 :: Nil))(pos ++ PosInExpr(0::1::1:: Nil)), useAt(pr, PosInExpr(1 :: Nil))(pos))
    }

    /* cut right subgoal */
    starter &
    cutR(if(cutInterior) interior else closure)(pos) <(
      skip,
      // Turn postcondition into interior
      implyR(pos) & generalize(interior)(pos) <(
        //@todo check always with doIfElse or use TryCatch exception?
        maxminGt & Idioms.doIfElse(_.subgoals.forall(s => !StaticSemantics.symbols(s(pos.top)).contains("t_".asVariable)))(
          useAt(Ax.openInvariantClosure)(pos) & Idioms.doIf(_.subgoals.length == 2)(
            //@todo may no longer be necessary at all, useAt seems to close precondition automatically now
            Idioms.<(
              backGt & backGe1 & hideL('Llast),
              backGe2 &
                (if(cutInterior) cohide2(AntePosition(seq.ante.length+1),pos) & interiorImplication
                else id)
            )
          ),
          DebuggingTactics.error("Inapplicable: t_ occurs")
        )
        ,
        cohideOnlyL('Llast) & interiorImplication
      )
    )
  })

  @Tactic(names="dC Closure",
    premises="Γ |- [x'=f(x)&Q∧closure(P)]interior(P), Δ ;; Γ |- interior(P)",
    conclusion="Γ |- [x'=f(x)&Q]P, Δ",
    displayLevel="browse", revealInternalSteps = true)
  val dCClosure : DependentPositionTactic = anon ((pos:Position) => dCClosure(true)(pos))

  /** Lemmas that can be proved only for specific instances of ODEs. */
  case class ODESpecific(ode: DifferentialProgram, variant: String => String = (x => x + "_")) {
    private val vars = DifferentialHelper.getPrimedVariables(ode)
    private val vvars = vars.zipWithIndex.map(vi => Variable(variant(vi._1.name), vi._1.index))
    private val ode2 = (vars,vvars).zipped.foldLeft(ode)((a, b) => URename(b._1, b._2)(a))

    private def tuple_of_list(ts: List[Term]) : Term = ts match {
      case Nil => Nothing
      case (t::Nil) => t
      case (t::ts) => Pair(t, tuple_of_list(ts))
    }
    private[btactics] def p(vs: List[Term]) = FuncOf(Function("p_", None, tuple_of_list(vs).sort, Real), tuple_of_list(vs))
    private[btactics] def P(vs: List[Term]) = PredOf(Function("P_", None, tuple_of_list(vs).sort, Bool), tuple_of_list(vs))
    private[btactics] def q(vs: List[Term]) = FuncOf(Function("q_", None, tuple_of_list(vs).sort, Real), tuple_of_list(vs))
    private[btactics] def r(vs: List[Term]) = PredOf(Function("r_", None, tuple_of_list(vs).sort, Bool), tuple_of_list(vs))

    private[btactics] val q_pat = q(vars)
    private[btactics] val p_pat = p(vars)
    private[btactics] val P_pat = P(vars)

    private def pos(t: Term) = Greater(t, Number(0))
    private def nonneg(t: Term) = GreaterEqual(t, Number(0))

    /**
      * A lemma of the following form for tuples x of dimension n
      * (
      *   P(x)                             &
      *   [{x'=f(x) & r(x) & P(x)}] q(x)>0  &
      *   \forall x [{x'=f(x) & r(x) & q(x)>=0}] P(x)' &
      *   \forall x P(x) <-> p(x)>=0
      * ) ->
      *     [{x'=f(x) & r(x)}] P(x)
      * */
    val dIopenClosedProvable: ProvableSig =
      proveBy(
        Imply(
          List(
            P(vars),
            Box(ODESystem(ode, And(r(vars), P(vars))), pos(q(vars))),
            FormulaTools.quantifyForall(vvars,
              Box(ODESystem(ode2, And(r(vvars), nonneg(q(vvars)))),
                DifferentialFormula(P(vvars)))),
            FormulaTools.quantifyForall(vvars, Equiv(P(vvars), nonneg(p(vvars)))),
          ).reduceRight(And),
          Box(ODESystem(ode, r(vars)), P(vars))),
        implyR(1) & andL(-1) & andL('Llast) & andL('Llast) &
          dR(And(r(vars), nonneg(p(vars))))(-2) &
          Idioms.<(
            skip,
            cohideOnlyL('Llast) &
              dW(1) &
              FOQuantifierTactics.allLs(vars)(-1, 1 :: Nil) &
              prop &
              done
          ) &
          TactixLibrary.generalize(nonneg(p(vars)))(1) & Idioms.<(skip, andL(-1) & FOQuantifierTactics.allLs(vars)('Llast) & prop & done) &
          //@todo always check with doIfElse or TryCatch instead?
          Idioms.doIfElse(_.subgoals.forall(s => !StaticSemantics.symbols(s(SuccPos(0))).contains("t_".asVariable)))(
            useAt(Ax.RIclosedgeq)(1) &
            andR(1) &
            Idioms.<(FOQuantifierTactics.allLs(vars)('Llast) & prop & done, skip) &
            composeb(1) &
            DW(1) &
            TactixLibrary.generalize(pos(q(vars)))(1) &
            Idioms.<(
              id,
              implyR(1) &
                assignb(1) &
                implyR(1) &
                /* @TODO: the following is somewhat close to ODEInvariance.lpstep */
                cutR(Or(pos(p(vars)), Equal(p(vars), Number(0))))(1) & Idioms.<(
                useAt(ODEInvariance.geq, PosInExpr(1 :: Nil))(1) & prop & done,
                implyR(1) &
                orL('Llast) < (
                  useAt(ODEInvariance.contAx, PosInExpr(1 :: Nil))(1) & prop & done,
                  dR(And(r(vars), nonneg(q(vars))), hide=false)(1) & Idioms.<(
                    useAt(Ax.UniqIff, PosInExpr(1 :: Nil))(1) &
                    andR(1) & Idioms.<(id, useAt(ODEInvariance.contAx, PosInExpr(1 :: Nil))(1) & id)
                    ,
                    andL('L) &
                    TactixLibrary.generalize(P(vars))(1) & Idioms.<(skip, andL(-1) & FOQuantifierTactics.allLs(vars)('Llast) & prop & done) &
                    DI(1) & implyR(1) & andR(1) & Idioms.<(
                      FOQuantifierTactics.allLs(vars)(-7) & prop & done
                      ,
                      cohideOnlyL(-6) &
                      FOQuantifierTactics.allLs(vars)(-1) &
                      DifferentialTactics.inverseDiffGhost(1) &
                      derive(1, 1 :: Nil) &
                      id
                    )
                  )
                )
              )
            ),
            DebuggingTactics.error("Inapplicable: t_ occurs")
          )
      )

    /**
      * If P is a closed set (i.e., can be normalized to p(x)>=0), applies differential induction by assuming P(x) in
      * the domain constraint and P(x)' <-> q(x)>=0 pointing strictly inwards ( q(x)>0 )
      *
      * if P(x)' normalizes to q(x)>=0:
      *
      * P(x)            [{x'=f(x) & r(x) & P(x)}] q(x)>0
      * ------------------------------------------------ dIClosed
      *             [{x'=f(x) & r(x)}] P(x)
      *  */
    val dIClosed = anon { (pos: Position, seq: Sequent) =>
      pos.checkTop
      seq.sub(pos) match {
        case Some(Box(ODESystem(ode, constraint), post)) =>
          import TaylorModelTactics.Timing._
          toc("== dIClosed")
          val postD = DifferentialHelper.lieDerivative(ode, post)
          toc("== lieDerivative")
          val post_semi = SimplifierV3.semiAlgNormalize(post)
          toc("== semiAlgNormalize post")
          val postD_semi = SimplifierV3.semiAlgNormalize(postD)
          toc("== semiAlgNormalize postD")
          (SimplifierV3.maxMinGeqNormalize(post_semi._1), SimplifierV3.maxMinGeqNormalize(postD_semi._1)) match {
            case ((GreaterEqual(p, Number(np)), Some(p_prv)),
            (GreaterEqual(q, Number(nq)), Some(q_prv))) if np == 0 && nq == 0 =>
            {
              toc("== maxMinGeqNormalize")
              val usubst = (UnificationMatch(p_pat, p) ++ UnificationMatch(q_pat, q) ++ UnificationMatch(P_pat, post)).usubst
              val lastpos = - seq.ante.length - 1
                useAt(dIopenClosedProvable(usubst), PosInExpr(1::Nil))(pos) &
                andR(pos) & Idioms.<( skip, andR(pos) & Idioms.<(skip, andR(pos))) &
                Idioms.<(
                    skip /* initial condition */,
                  tocTac("== Tactic start") &
                  dW(pos) & implyRi /* (open) differential invariant */,
                  tocTac("== dW") &
                  cohideR(pos) & allR(pos)*vars.length & derive(pos++PosInExpr(1::Nil)) &
                    DE(pos) & Dassignb(pos ++ PosInExpr(1::Nil))*vars.length & dW(pos) &
                    tocTac("== DE") &
                    QE & done,
                  tocTac("== QE") &
                  cohideR(pos) & allR(pos)*vars.length &
                    useAt(post_semi._2.get, PosInExpr(0::Nil))(1, 0::Nil) &
                    useAt(p_prv, PosInExpr(0::Nil))(1, 0::Nil) &
                    byUS(Ax.equivReflexive) &
                    tocTac("== done") &
                    done
                )
            }
            case unexpected =>
              throw new RuntimeException("dIClosed: maxMinGeqNormalize produced something unexpected: " + unexpected)
          }
        case Some(e) => throw new TacticInapplicableFailure("dIClosed only applicable to box ODEs, but got " + e.prettyString)
        case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
      }
    }
  }

}
