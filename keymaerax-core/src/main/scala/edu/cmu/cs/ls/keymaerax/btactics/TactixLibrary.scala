/**
 * Copyright (c) Carnegie Mellon University. CONFIDENTIAL
 * See LICENSE.txt for the conditions of this license.
 */
package edu.cmu.cs.ls.keymaerax.btactics

import java.io.File

import edu.cmu.cs.ls.keymaerax.bellerophon._
import edu.cmu.cs.ls.keymaerax.bellerophon.parser.BelleParser
import edu.cmu.cs.ls.keymaerax.btactics.Idioms.{?, must}
import edu.cmu.cs.ls.keymaerax.btactics.TacticFactory._
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.infrastruct.Augmentors._
import edu.cmu.cs.ls.keymaerax.btactics.InvariantGenerator.GenProduct
import edu.cmu.cs.ls.keymaerax.btactics.TacticIndex.{BranchRecursor, TacticRecursor, TacticRecursors}
import edu.cmu.cs.ls.keymaerax.infrastruct.{AntePosition, PosInExpr, Position, SuccPosition, UnificationMatch}
import edu.cmu.cs.ls.keymaerax.lemma.{Lemma, LemmaDBFactory}
import edu.cmu.cs.ls.keymaerax.btactics.macros.{DerivationInfo, Tactic, TacticInfo}
import edu.cmu.cs.ls.keymaerax.parser.ArchiveParser
import edu.cmu.cs.ls.keymaerax.pt.ProvableSig
import edu.cmu.cs.ls.keymaerax.tools.{ToolEvidence, ToolOperationManagement}
import edu.cmu.cs.ls.keymaerax.tools.ext.QETacticTool
import org.slf4j.LoggerFactory

import scala.collection.immutable.{List, _}
import scala.util.Try

/**
  * Tactix: Main tactic library with simple interface.
  * This library features all main tactics for the most common cases.
  *
  * For tactics implementing built-in rules such as sequent proof rules,
  * elaborate documentation can be found in the [[edu.cmu.cs.ls.keymaerax.core.Rule prover kernel]].
  *
  * Main search tactics that combine numerous other tactics for automation purposes include:
  *   - [[TactixLibrary.master]] automatic proof search
  *   - [[TactixLibrary.autoClose]] automatic proof search if that successfully proves the given property
  *   - [[TactixLibrary.normalize]] normalize to sequent normal form
  *   - [[TactixLibrary.unfoldProgramNormalize]] normalize to sequent normal form, avoiding unnecessary branching
  *   - [[TactixLibrary.prop]] propositional logic proving
  *   - [[TactixLibrary.QE]] prove real arithmetic
  *   - [[TactixLibrary.ODE]] proving properties of differential equations
  *   - [[TactixLibrary.step]] performs one canonical simplifying proof step
  *   - [[TactixLibrary.chase]] chase the given formula away by automatic reduction proofs
  *
  * The tactic library also includes important proof calculus subcollections:
  *   - [[HilbertCalculus]]: Hilbert Calculus for differential dynamic logic.
  *   - [[SequentCalculus]]: Sequent Calculus for propositional and first-order logic.
  *   - [[HybridProgramCalculus]]: Hybrid program derived proof rules for differential dynamic logic.
  *   - [[DifferentialEquationCalculus]]: Differential equation proof rules for differential dynamic logic.
  *   - [[UnifyUSCalculus]]: Automatic unification-based Uniform Substitution Calculus with indexing.
  *
  * @author Andre Platzer
  * @author Stefan Mitsch
  * @see Andre Platzer. [[https://doi.org/10.1007/s10817-016-9385-1 A complete uniform substitution calculus for differential dynamic logic]]. Journal of Automated Reasoning, 59(2), pp. 219-266, 2017.
  * @see Andre Platzer. [[https://doi.org/10.1007/978-3-319-21401-6_32 A uniform substitution calculus for differential dynamic logic]].  In Amy P. Felty and Aart Middeldorp, editors, International Conference on Automated Deduction, CADE'15, Berlin, Germany, Proceedings, LNCS. Springer, 2015.
  * @see Andre Platzer. [[https://doi.org/10.1007/978-3-319-63588-0 Logical Foundations of Cyber-Physical Systems]]. Springer, 2018.
  * @see [[HilbertCalculus]]
  * @see [[SequentCalculus]]
  * @see [[DifferentialEquationCalculus]]
  * @see [[HybridProgramCalculus]]
  * @see [[UnifyUSCalculus]]
  * @see [[Ax]]
  * @see [[AxiomInfo]]
  * @see [[edu.cmu.cs.ls.keymaerax.core.Rule]]
  * @see [[ToolProvider]]
  */
object TactixLibrary extends HilbertCalculus
  with SequentCalculus
  with DifferentialEquationCalculus
  with HybridProgramCalculus {
  import Generator.Generator

  private val logger = LoggerFactory.getLogger(getClass) //@note instead of "with Logging" to avoid cyclic dependencies

  // active invariant generators etc.

  /** "Generator" that provides (hardcoded or user-provided) loop invariants and differential invariants to use.
    * @see [[TactixInit]]
    * @see [[InvariantGenerator]] */
  def invSupplier: Generator[GenProduct] = TactixInit.invSupplier
  /** Default generator for loop invariants to use.
    * @see [[TactixInit]]
    * @see [[InvariantGenerator]] */
  def loopInvGenerator: Generator[GenProduct] = TactixInit.loopInvGenerator
  /** Default generator for differential invariants to use.
    * @see [[TactixInit]]
    * @see [[InvariantGenerator]] */
  def differentialInvGenerator: Generator[GenProduct] = TactixInit.differentialInvGenerator
  /** Default generator that provides loop invariants and differential invariants to use.
    * @see [[InvariantGenerator]] */
  val invGenerator: Generator[GenProduct] = (sequent, pos) => sequent.sub(pos) match {
    case Some(Box(_: ODESystem, _)) => differentialInvGenerator(sequent, pos)
    case Some(Box(_: Loop, _))      => loopInvGenerator(sequent, pos)
    case Some(_) => throw new IllegalArgumentException("ill-positioned " + pos + " does not give a differential equation or loop in " + sequent)
    case None    => throw new IllegalArgumentException("ill-positioned " + pos + " undefined in " + sequent)
  }

  // Hilbert calculus axioms @see [[HilbertCalculus]]
  // Propositional/first-order sequent calculus @see [[SequentCalculus]]
  // Hybrid program derived rules @see [[HybridProgramCalculus]]
  // Differential equation derived rules @see [[DifferentialEquationCalculus]]


  // high-level generic proof automation

  /** step: one canonical simplifying proof step at the indicated formula/term position (unless @invariant etc needed) */
  @Tactic(longDisplayName = "Program Step", revealInternalSteps = true)
  val step : DependentPositionTactic = anon ((pos: Position) =>
    //@note AxiomIndex (basis for HilbertCalculus.stepAt) hands out assignment axioms, but those fail in front of an ODE -> try assignb if that happens
    (if (pos.isTopLevel) stepAt(sequentStepIndex(pos.isAnte)(_))(pos)
     else HilbertCalculus.stepAt(pos))
      //@todo optimizable: move assignb tactic into AxIndex once supported
    |! assignb(pos))

  /** Normalize to sequent form. Keeps branching factor of [[tacticChase]] restricted to [[orL]], [[implyL]], [[equivL]], [[andR]], and [[equivR]]. */
  @Tactic("normalize", longDisplayName = "Normalize to Sequent Form", revealInternalSteps = true)
  lazy val normalize: BelleExpr =  normalize(orL, implyL, equivL, andR, equivR)
  /** Normalize to sequent form. Keeps branching factor of [[tacticChase]] restricted to `beta` rules. */
  def normalize(beta: AtPosition[_ <: BelleExpr]*): BelleExpr = anon (allTacticChase(
    new DefaultTacticIndex {
      override def tacticsFor(expr: Expression): (List[AtPosition[_ <: BelleExpr]], List[AtPosition[_ <: BelleExpr]]) = expr match {
        case f@Not(_)      if f.isPredicateFreeFOL => (Nil, Nil)
        case f@And(_, _)   if f.isPredicateFreeFOL => (andL::Nil, Nil)
        case f@Or(_, _)    if f.isPredicateFreeFOL => (Nil, orR::Nil)
        case f@Imply(_, _) =>
          if (f.isPredicateFreeFOL) (Nil, implyR::Nil)
          else (PropositionalTactics.autoMP::implyL::Nil, implyR::Nil)
        case f@Equiv(_, _) if f.isPredicateFreeFOL => (Nil, Nil)
        case _ => super.tacticsFor(expr)
      }
    }
  )(notL::andL::notR::implyR::orR::PropositionalTactics.autoMP::allR::TacticIndex.allLStutter::
    existsL::TacticIndex.existsRStutter::DLBySubst.safeabstractionb::dW::step::ProofRuleTactics.closeTrue::
    ProofRuleTactics.closeFalse::Nil ++ beta:_*))

  /** Follow program structure when normalizing but avoid branching in typical safety problems (splits andR but nothing else). Keeps branching factor of [[tacticChase]] restricted to [[andR]]. */
  @Tactic(codeName = "unfold", longDisplayName = "Unfold Program Structure", revealInternalSteps = true)
  val unfoldProgramNormalize: BelleExpr = anon {normalize(andR)}

  /** Exhaustively (depth-first) apply tactics from the tactic index, restricted to the tactics in `restrictTo`, to chase away.
    * Unlike [[chase]], tacticChase will use propositional proof rules and possibly branch
    * @see [[chase]] */
  def tacticChase(tacticIndex: TacticIndex = new DefaultTacticIndex)
                 (restrictTo: AtPosition[_ <: BelleExpr]*)
                 (expected: Option[Formula]): DependentPositionTactic = anon ((pos: Position, seq: Sequent) => {
    val restrictions = restrictTo.toList

    /** Apply the canonical tactic for the formula at position `pos`; exhaustively depth-first search on resulting other formulas */
    def atPos(except: Option[Position]): DependentPositionTactic = anon ((pos: Position, s: Sequent) => {
      if (except.contains(pos)) skip
      else s.sub(pos) match {
        case Some(fml) =>
          val si = s.succ.indexOf(fml)
          if (pos.isAnte && si >= 0) closeId(pos.checkAnte.top, SuccPos(si))
          else {
            val ai = s.ante.indexOf(fml)
            if (pos.isSucc && ai >= 0) closeId(AntePos(ai), pos.checkSucc.top)
            else {
              val (ta, ts) = tacticIndex.tacticsFor(fml)
              if (pos.isAnte) ta.intersect(restrictions).map(applyAndRecurse(_, pos, s)).reduceOption(_ | _).getOrElse(skip)
              else ts.intersect(restrictions).map(applyAndRecurse(_, pos, s)).reduceOption(_ | _).getOrElse(skip)
            }
          }
        case _ => skip
      }
    })

    /** Apply `atPos` at the specified position, or search for the expected formula if it cannot be found there. */
    def atOrSearch(p: PositionLocator): DependentTactic = anon ((s: Sequent) => p match {
      case Fixed(pp, Some(fml), exact) =>
        if (( exact && s.sub(pp).contains(fml)) ||
            (!exact && s.sub(pp).exists(UnificationMatch.unifiable(fml, _).isDefined))) atPos(None)(Fixed(pp))
        else if (pp.isAnte) atPos(Some(pp))(Find.FindL(0, Some(fml), exact=exact))
        else                atPos(Some(pp))(Find.FindR(0, Some(fml), exact=exact))
      case _ => atPos(None)(p)
    })

    /** Do all the tactics of a branch in sequence. */
    def applyBranchRecursor(rec: BranchRecursor): BelleExpr =
      //@note onAll tries on too many branches, but optional atOrSearch compensates for this
      rec.positions.map(r => onAll(?(atOrSearch(r)))).reduce(_&_)

    /** Turn branches (if any) into a branching tactic. */
    def applyRecursor(rec: TacticRecursor): BelleExpr = rec.branches match {
      case Nil => skip
      case r::Nil => onAll(applyBranchRecursor(r))
      case r => DebuggingTactics.assertProvableSize(r.length, new IllFormedTacticApplicationException(_)) &
        BranchTactic(r.map(applyBranchRecursor))
    }

    /** Execute `t` at pos, read tactic recursors and schedule followup tactics. */
    def applyAndRecurse(t: AtPosition[_ <: BelleExpr], pos: Position, s: Sequent): BelleExpr = {
      //@note tactic recursors also check top-level vs. non-toplevel tactic applicability
      tacticIndex.tacticRecursors(t)(s, pos) match {
        case Left(r@TacticRecursor(branches)) =>
          //@note Left(recursors) execute tactic then recurse;
          // if recursor continues working on the same position: insist that t made progress
          val tactic =
            if (branches.exists(_.positions.exists({ case Fixed(pp, _, _) => pp == pos case _ => false }))) must(t(new Fixed(pos)))
            else t(new Fixed(pos))
          if (branches.nonEmpty) tactic & Idioms.doIf(!_.isProved)(applyRecursor(r))
          else tactic
        case Right(r@TacticRecursor(branches)) =>
          //@note skip tactic, recurse right away
          if (branches.exists(_.positions.exists({ case Fixed(pp, _, _) => pp == pos case _ => false })))
            throw new IllegalArgumentException("Tactic index error: skip recursor must not point to original position")
          else applyRecursor(r)
      }
    }

    seq.sub(pos) match {
      case Some(fml: Formula) =>
        if (expected.isEmpty || expected.contains(fml)) onAll(atPos(None)(pos, fml))
        else onAll(atPos(Some(pos))(if (pos.isAnte) 'L else 'R, expected.get))
      case Some(e) => throw new TacticInapplicableFailure("TacticChase is only applicable at formulas, but got " + e.prettyString)
      case None =>
        if (expected.isDefined) onAll(atPos(Some(pos))(if (pos.isAnte) 'L else 'R, expected.get))
        else throw new IllFormedTacticApplicationException("Position " + pos + " points outside sequent")
    }
  })

  /** Exhaustively chase all formulas in the sequent.
    * @see [[tacticChase]] */
  def allTacticChase(tacticIndex: TacticIndex = new DefaultTacticIndex)(restrictTo: AtPosition[_ <: BelleExpr]*): BelleExpr = SaturateTactic(
    //@note Execute on formulas in order of sequent; might be useful to sort according to some tactic priority.
    Idioms.doIf(!_.isProved)(onAll(anon ((ss: Sequent) => {
      //@note prevent access of undefined positions if earlier chase moved formulas; subgoals.forall since tactic chase is a singlegoal tactic
      ss.succ.zipWithIndex.map({ case (fml, i) => ?(Idioms.doIf(_.subgoals.forall(i < _.succ.size))(
        onAll(tacticChase(tacticIndex)(restrictTo:_*)(Some(fml))(SuccPosition.base0(i))))) }).reduceRightOption[BelleExpr](_&_).getOrElse(skip)
    }))) &
    Idioms.doIf(!_.isProved)(onAll(anon ((ss: Sequent) => {
      //@note prevent access of undefined positions if earlier chase moved formulas; subgoals.forall since tactic chase is a singlegoal tactic
      ss.ante.zipWithIndex.map({ case (fml, i) => ?(Idioms.doIf(_.subgoals.forall(i < _.ante.size))(
        onAll(tacticChase(tacticIndex)(restrictTo:_*)(Some(fml))(AntePosition.base0(i))))) }).reduceRightOption[BelleExpr](_&_).getOrElse(skip)
    })))
  )

  @Tactic("chaseAt", longDisplayName = "Decompose", codeName = "chaseAt", revealInternalSteps = true)
  def chaseAtX: DependentPositionTactic = chaseAt()(
    TactixLibrary.andL, TactixLibrary.implyR, TactixLibrary.orR, TactixLibrary.allR, TacticIndex.allLStutter,
    TactixLibrary.existsL, TacticIndex.existsRStutter,
    ProofRuleTactics.closeTrue, ProofRuleTactics.closeFalse
  )
  /** Chases program operators according to [[AxIndex]] or tactics according to `tacticIndex` (restricted to tactics
    * in `restrictTo`) at a position. */
  def chaseAt(tacticIndex: TacticIndex = new DefaultTacticIndex)
             (restrictTo: AtPosition[_ <: BelleExpr]*): DependentPositionTactic = anon ((pos: Position, seq: Sequent) => {
    seq.sub(pos) match {
      case Some(e) =>
        if (AxIndex.axiomsFor(e).nonEmpty) {
          chase(pos)
        } else {
          val tactics = tacticIndex.tacticFor(e, restrictTo.toList)
          if (pos.isAnte && tactics._1.isDefined || pos.isSucc && tactics._2.isDefined) {
            tacticChase(tacticIndex)(restrictTo:_*)(None)(pos)
          } else {
            throw new TacticInapplicableFailure("Chase is not applicable, please try other tactics at position " + pos.prettyString + " in\n" + seq.prettyString)
          }
        }
      case None => throw new IllFormedTacticApplicationException("Position " + pos.prettyString + " is not a valid position in " + seq.prettyString)
    }
  })

  @Tactic(longDisplayName = "Unfold Propositional", revealInternalSteps = true)
  val prop: BelleExpr = anon {allTacticChase()(notL, andL, orL, implyL, equivL, notR, implyR, orR, andR, equivR,
                                                ProofRuleTactics.closeTrue, ProofRuleTactics.closeFalse)}

  /** Automated propositional reasoning, only keeps result if proved. */
  @Tactic(longDisplayName = "Prove Propositional", revealInternalSteps = true)
  val propClose: BelleExpr = anon {prop & DebuggingTactics.done("Not provable propositionally, please try other proof methods")}
  @Tactic(longDisplayName = "Prove Propositional", revealInternalSteps = true)
  val propAuto: BelleExpr = propClose

  /** Master implementation with tactic `loop` for nondeterministic repetition and `odeR` for
    * differential equations in the succedent.
    * `keepQEFalse` indicates whether or not QE results "false" at the proof leaves should be kept or undone. */
  def master(loop: AtPosition[_ <: BelleExpr], odeR: AtPosition[_ <: BelleExpr],
             keepQEFalse: Boolean): BelleExpr = anon {
    /** Tactic index that hands out loop tactics and configurable ODE tactics. */
    val autoTacticIndex = new DefaultTacticIndex {
      override def tacticRecursors(tactic: BelleExpr): TacticRecursors = {
        //@note BEWARE! .eq for true reference comparison! needed since anon tactic factories create indistinguishable
        // case classes (all have the same name, none mentions the concrete tactic instance)
        if (tactic.eq(loop)) {
          //@todo positions? what to expect there?
          (_: Sequent, p: Position) => Left(TacticRecursor(
            BranchRecursor(Fixed(p) :: Nil) ::
            BranchRecursor(Fixed(p) :: Nil) ::
            BranchRecursor(Fixed(p) :: Nil) :: Nil))
        } else if (tactic.eq(odeR)) {
          (_: Sequent, p: Position) => Left(TacticRecursor(BranchRecursor(Fixed(p) :: Nil) :: Nil))
        } else super.tacticRecursors(tactic)
      }

      override def tacticsFor(expr: Expression): (List[AtPosition[_ <: BelleExpr]], List[AtPosition[_ <: BelleExpr]]) = expr match {
        case Box(_: Loop, _) => (Nil, DLBySubst.safeabstractionb::loop::Nil)
        case Box(_: ODESystem, _) => (TactixLibrary.solve::Nil, DLBySubst.safeabstractionb::odeR::solve::dWPlus::Nil)
        case f@Not(_)      if f.isPredicateFreeFOL => (Nil, Nil)
        case f@And(_, _)   if f.isPredicateFreeFOL => (TactixLibrary.andL::Nil, Nil)
        case f@Or(_, _)    if f.isPredicateFreeFOL => (Nil, TactixLibrary.orR::Nil)
        case f@Imply(_, _) =>
          if (f.isPredicateFreeFOL) (Nil, TactixLibrary.implyR::Nil)
          else (PropositionalTactics.autoMP::implyL::Nil, implyR::Nil)
        case f@Equiv(_, _) if f.isPredicateFreeFOL => (Nil, Nil)
        case _ => super.tacticsFor(expr)
      }
    }

    def composeChase: DependentPositionTactic = anon ((pos: Position, seq: Sequent) => {
      val decompose = Idioms.mapSubpositions(pos, seq, {
        case (Box(Compose(_, _), _), pp: Position) => Some(chase(3, 3, (e: Expression) => e match {
          case Box(Compose(_, _), _) => Ax.composeb :: Nil
          case _ => Nil
        })(pp))
        case _ => None
      })

      decompose.reduceOption[BelleExpr](_ & _).getOrElse(skip)
    })

    def odeInContext(odeR: AtPosition[_ <: BelleExpr]): DependentPositionTactic = anon ((pos: Position, seq: Sequent) => {
      val solvers = Idioms.mapSubpositions(pos, seq, {
        case (Box(ODESystem(_, _), q), pp: Position) =>
          if (pp.isTopLevel) {
            if (q.isFOL) Some(odeR(pp))
            //@note chase may make progress on some but not all postconditions (e.g. not on loops)
            else Some(chase(pp ++ PosInExpr(1::Nil)) & SimplifierV3.simplify(pp ++ PosInExpr(1::Nil)) & odeR(pp))
          } else Some(solve(pp))
        case _ => None
      })
      solvers.reduceOption[BelleExpr](_ & _).getOrElse(skip)
    })

    def decomposeToODE: BelleExpr = anon ((seq: Sequent) => {
      if (seq.isFOL) {
        skip /* master continues */
      } else {
        SaturateTactic(close | alphaRule | loop('R) /* loopauto recurses into master */) &
          //@note loopauto should have closed all goals; but continue for programs without loop
          Idioms.doIf(!_.isProved)( /* loop-free: decompose and handle ODE in context before splitting */
            SaturateTactic(composeChase('R)) &
            SaturateTactic(odeInContext(odeR)('R)) /* master continues after ODEs in context */)
      }
    })

    val autoQE = QE /* @todo  Idioms.must(ArithmeticSpeculativeSimplification.autoMonotonicityTransform) & DebuggingTactics.print("autoQE") &
      (QE(timeout = Some(5)) | DebuggingTactics.print("smartQE") & ArithmeticSpeculativeSimplification.speculativeQE) |
      (QE(timeout = Some(5)) | ArithmeticSpeculativeSimplification.speculativeQE)*/

    onAll(decomposeToODE) &
    onAll(Idioms.doIf(!_.isProved)(close |
      SaturateTactic(onAll(allTacticChase(autoTacticIndex)(notL, andL, notR, implyR, orR, allR,
        TacticIndex.allLStutter, existsL, TacticIndex.existsRStutter, step, orL,
        implyL, equivL, ProofRuleTactics.closeTrue, ProofRuleTactics.closeFalse,
        andR, equivR, DLBySubst.safeabstractionb, loop, odeR, solve))) & //@note repeat, because step is sometimes unstable and therefore recursor doesn't work reliably
        Idioms.doIf(!_.isProved)(onAll(
          //@note apply equalities inside | to undo in case branches do not close
          (EqualityTactics.applyEqualities & Idioms.must(DifferentialTactics.endODEHeuristic) & autoQE & done)
            | ?(EqualityTactics.applyEqualities & autoQE & (if (keepQEFalse) nil else done))))))
  }

  /** master: master tactic that tries hard to prove whatever it could. `keepQEFalse` indicates whether or not a
    * result `false` of a QE step at the leaves is kept or undone (i.e., reverted to the QE input sequent).
 *
    * @see [[autoClose]] */
  def master(gen: Generator[GenProduct] = invGenerator,
             keepQEFalse: Boolean = true): BelleExpr = auto(gen, if (keepQEFalse) None else Some(False))

  /**
   * auto: tactic that tries hard to prove whatever it could.
 *
   * @see [[autoClose]] */
  @Tactic(longDisplayName = "Unfold Automatically")
  def auto(generator: Generator[GenProduct], keepQEFalse: Option[Formula] = None): InputTactic = inputanon {
    master(loopauto(generator), ODE, keepQEFalse.getOrElse(True) == True)
  }
  @Tactic(names="master", codeName="master", longDisplayName = "Unfold Automatically")
  def masterX(generator: Generator[GenProduct], keepQEFalse: Option[Formula] = None): InputTactic = auto(generator, keepQEFalse)

  /** autoClose: automatically try hard to prove the current goal if that succeeds.
    * @see [[master]] */
  @Tactic(longDisplayName = "Prove Automatically")
  def autoClose: DependentTactic = anons { (_: ProvableSig) =>
    master(loopauto(InvariantGenerator.loopInvariantGenerator), ODE, keepQEFalse=true) & DebuggingTactics.done("Automation failed to prove goal") }

  /** explore: automatically explore a model with all annotated loop/differential invariants, keeping failed attempts
    * and only using ODE invariant generators in absence of annotated invariants and when they close goals. */
  @Tactic("explore", longDisplayName = "Explore Provability", revealInternalSteps = true)
  def explore(gen: Generator[GenProduct]): InputTactic = inputanon {master(anon ((pos: Position, seq: Sequent) => (gen, seq.sub(pos)) match {
    case (cgen: ConfigurableGenerator[GenProduct], Some(Box(prg@Loop(_), _))) if cgen.products.contains(prg) =>
      logger.info("Explore uses loop with annotated invariant")
      //@note bypass all other invariant generators except the annotated invariants, pass on to loop
      ChooseSome(
        () => try {
          gen(seq, pos).iterator.map(_._1)
        } catch {
          case err: Exception =>
            logger.warn("ChooseSome: error listing options " + err, err)
            List[Formula]().iterator
        },
        (inv: Formula) => loop(inv)(pos) & onAll(explore(gen))
      )
    case (_, Some(Box(prg@Loop(_), _))) => throw new IllegalArgumentException("Explore requires a configurable generator.")
    case _ => throw new InputFormatFailure("Explore requires a loop invariant to explore. Please use @invariant annotation in the input model")
  }), /*@todo restrict ODE invariant generator */ ODE, keepQEFalse=false)}

  /*******************************************************************
    * unification and matching based auto-tactics
 *
    * @see [[UnifyUSCalculus]]
    *******************************************************************/

//  /** US: uniform substitution ([[edu.cmu.cs.ls.keymaerax.core.UniformSubstitutionRule USubst]])
//    * @see [[edu.cmu.cs.ls.keymaerax.core.UniformSubstitutionRule]]
//    * @see [[edu.cmu.cs.ls.keymaerax.core.USubst]]
//    */
//  def US(subst: USubst, origin: Sequent): BuiltInTactic = ProofRuleTactics.US(subst, origin)


  // modalities

  /** GVR abstractionb: turns `[a]p` into `\forall BV(a) p` by universally quantifying over all variables modified in program `a`.
    * Returns a tactic to abstract a box modality to a formula that quantifies over the bound variables in the program
    * of that modality.
    * @example {{{
    *           |- \forall x x>0
    *         ------------------abstractionb(1)
    *           |- [x:=2;]x>0
    * }}}
    * @example {{{
    *           |- x>0 & z=1 -> [z:=y;]\forall x x>0
    *         --------------------------------------abstractionb(1, 1::1::Nil)
    *           |- x>0 & z=1 -> [z:=y;][x:=2;]x>0
    * }}}
    * @example {{{
    *           |- x>0
    *         ---------------abstractionb(1)
    *           |- [y:=2;]x>0
    * }}}
    * @example {{{
    *          x<=0  |- \forall y \forall z x<=z^2
    *         ------------------------------------abstractionb(1)
    *          x<=0  |- [y:=2;z:=z+1;]x<=z^2
    * }}}
    */
  lazy val abstractionb       : DependentPositionTactic = DLBySubst.abstractionb

  /** 'position' tactic t with universal abstraction at the same position afterwards
    * @see [[abstractionb]] */
  def withAbstraction(t: AtPosition[_ <: BelleExpr]): DependentPositionTactic = new DependentPositionTactic("with abstraction") {
    override def factory(pos: Position): DependentTactic = new SingleGoalDependentTactic(name) {
      override def computeExpr(sequent: Sequent): BelleExpr = {
        require(pos.isTopLevel, "with abstraction only at top-level")
        sequent(pos.checkTop) match {
          case Box(a, p) =>
            t(pos) & abstractionb(pos) & (if (pos.isSucc) SaturateTactic(allR(pos)) else skip)
          case Diamond(a, p) if pos.isAnte => ???
        }
      }
    }
  }

  /** loop: prove a property of a loop by induction, if the given invariant generator finds a loop invariant
    * @see [[HybridProgramCalculus.loop(Formula)]] */
  def loop(gen: Generator[GenProduct]): DependentPositionTactic = new DependentPositionTactic("I gen") {
    override def factory(pos: Position): DependentTactic = new SingleGoalDependentTactic(name) {
      override def computeExpr(sequent: Sequent): BelleExpr = loop(nextOrElse(gen(sequent, pos).map(_._1).iterator,
        throw new BelleNoProgress("Unable to generate an invariant for " + sequent(pos.checkTop) + " at position " + pos)))(pos)
      private def nextOrElse[A](it: Iterator[A], otherwise: => A) = if (it.hasNext) it.next else otherwise
    }
  }
  @Tactic("loopAuto", codeName = "loopAuto", longDisplayName = "Loop with Invariant Automation", conclusion = "Γ |- [a*]P, Δ")
  def loopautoX(gen: Generator[GenProduct]): DependentPositionWithAppliedInputTactic = inputanon { (pos: Position, seq: Sequent) => loopauto(gen)(pos) }

  /** loop: prove a property of a loop automatically by induction, trying hard to generate loop invariants.
    * @see [[HybridProgramCalculus.loop(Formula)]] */
  def loopauto(gen: Generator[GenProduct] = loopInvGenerator): DependentPositionTactic =
      anon ((pos: Position, seq: Sequent) => seq.sub(pos) match {
    case Some(Box(Loop(_), _) | Diamond(Loop(_), _)) =>
      //@hack invSupplier collects invariant annotation during parsing; prefer those over the ones generated by loopPostMaster
      (invSupplier, seq.sub(pos)) match {
        case (cgen: ConfigurableGenerator[GenProduct], Some(Box(prg@Loop(_), _))) if cgen.products.contains(prg) =>
          logger.info("LoopAuto uses loop with annotated invariant")
          //@note bypass all other invariant generators except the annotated invariants, pass on to loop
          ChooseSome(
            () => try {
              invSupplier(seq, pos).iterator.map(_._1)
            } catch {
              case err: Exception =>
                logger.warn("ChooseSome: error listing options " + err, err)
                List[Formula]().iterator
            },
            (inv: Formula) => loop(inv)(pos) & onAll(autoClose & done) & done
          )
        case _ =>
          logger.info("LoopAuto with loopPostMaster for typical hybrid models plus fallback invariant generator")
//@todo     loopPostMaster(gen)(pos) & done ||
            ChooseSome(
              () => try {
                gen(seq, pos).iterator.map(_._1)
              } catch {
                case err: Exception =>
                  logger.warn("ChooseSome: error listing options " + err, err)
                  List[Formula]().iterator
              },
              (inv: Formula) => DLBySubst.cexLoop(inv)(pos) & loop(inv)(pos) & onAll(autoClose) & done
            )
      }
    case _ => throw new TacticInapplicableFailure("Loopauto is applicable to nondeterministic repetition only")
  })

  /** loopSR: cleverly prove a property of a loop automatically by induction, trying hard to generate loop invariants.
    * Uses [[SearchAndRescueAgain]] to avoid repetitive proving.
    * @see [[loopauto]]
    * @see Andre Platzer. [[https://doi.org/10.1007/s10817-016-9385-1 A complete uniform substitution calculus for differential dynamic logic]]. Journal of Automated Reasoning, 59(2), pp. 219-266, 2017.
    *      Example 32. */
  def loopSR(gen: Generator[GenProduct]): DependentPositionTactic = InvariantProvers.loopSR(gen)
  /** loopPostMaster: search-and-rescue style automatic loop induction based on successive generator gen.
    * Uses [[SearchAndRescueAgain]] to avoid repetitive proving.
    * Present implementation needs differential equations to occur somewhere within the loop.
    * @author Andre Platzer
    * @author Stefan Mitsch
    * @see Andre Platzer. [[https://doi.org/10.1007/s10817-016-9385-1 A complete uniform substitution calculus for differential dynamic logic]]. Journal of Automated Reasoning, 59(2), pp. 219-266, 2017.
    *      Example 32. */
  def loopPostMaster(gen: Generator[GenProduct]): DependentPositionTactic = InvariantProvers.loopPostMaster(gen)

  /** I: prove a property of a loop [{a}*]P by induction axiom as P & [{a}*](P->[a]P) for hybrid systems.
    * @see [[loop()]]
    */
  //def I      : DependentPositionTactic = useAt(DerivedAxioms.Ieq)

  /** throughout: prove a property of a loop by induction with the given loop invariant (hybrid systems) that
    * holds throughout the steps of the loop body.
    * Wipes conditions that contain bound variables of the loop.
    * {{{
    *   use:                      init:        steps:
    *   I, G_cnst |- p, D_cnst    G |- I, D    I, G_cnst |- [a]I,    D_cnst
    *                                          I, G_cnst |- [b;c;]I, D_cnst
    *                                          I, G_cnst |- [d;]I,   D_cnst
    *   -------------------------------------------------------------------
    *   G |- [{a;{b;c;}d}*]p, D
    * }}}
    */
  def throughout(invariant: Formula): DependentPositionTactic = DLBySubst.throughout(invariant)

  /**
    * Loop convergence: prove a diamond property of a loop by induction with a variant for progress.
    * {{{
    *   init:                       use:                         step:
    *   G |- exists v. J(v), D    v<=0, J(v), consts |- p    v>0, J(v), consts |- <a>J(v-1)
    *   --------------------------------------------------------------------------------------------
    *   G |- <{a}*>p, D
    * }}}
    * @param variant The variant property or convergence property in terms of new variable `v`.
    * @example The variant J(v) ~> (v = z) is specified as v=="v".asVariable, variant == "v = z".asFormula
    */
  def con(v: Variable, variant: Formula, pre: BelleExpr = SaturateTactic(alphaRule)): DependentPositionTactic = DLBySubst.con(v, variant, pre)


  // major differential equation automation

  /** ODE: try to prove a property of a differential equation automatically.
    *
    * @see [[solve]]
    * @todo @see [[dC]]
    * @see [[dI]]
    * @see [[dW]]
    * @see [[dG]]
    */
  @Tactic(longDisplayName = "ODE Auto", revealInternalSteps = true)
  lazy val ODE: DependentPositionTactic = anon ((pos: Position, seq: Sequent) => must({
    // use and check invSupplier (user-defined annotations from input file)
    val invs = invSupplier(seq, pos).toList
    invs.map(inv => dC(inv._1)(pos) & Idioms.doIf(_.subgoals.size == 2)(Idioms.<(
      skip,
      //@todo how to handle multiple archive entries with same dynamics but conflicting annotations?
      (if (pos.isTopLevel) SaturateTactic(DifferentialTactics.odeInvariant(tryHard = true, useDw = true)(pos) | ODEInvariance.dRI(pos))
       else DifferentialTactics.diffInd()(pos)) &
        Idioms.doIf(p => p.subgoals.nonEmpty && p.subgoals.forall(_.isFOL))(onAll(QE)) &
        DebuggingTactics.assertProvableSize(0, (details: String) => new UnprovableAnnotatedInvariant(
          "User-supplied invariant " + inv._1.prettyString + " not proved; please double-check and adapt invariant.\nFor example, invariant may hold on some branches but not all: consider using conditional annotations @invariant( (x'=0 -> invA), (x'=2 -> invB) ).\n" + details))
    ))).reduceOption[BelleExpr](_ & _).getOrElse(skip) &
      (if (pos.isTopLevel && invs.nonEmpty) dW(pos) & SaturateTactic(alphaRule) & SimplifierV3.fullSimplify & QE & done | DifferentialTactics.mathematicaSplittingODE(pos)
       else if (pos.isTopLevel) DifferentialTactics.mathematicaSplittingODE(pos) |
        (seq.sub(pos) match {
          // make progress on nonFOL postcondition (mathematicaSplittingODE only handles FOL postcondition)
          case Some(Box(ODESystem(_, q), p)) if q != True && !p.isFOL => dWPlus(pos)
          case _ => skip
        })
       else DifferentialTactics.diffInd()(pos) & SimplifierV3.simplify(pos))
  }, Some("ODE automation was neither able to prove the postcondition invariant nor automatically find new ODE invariants. Try annotating the ODE with additional invariants or refining the evolution domain with a differential cut.")))

  /**
    * Attempts to prove ODE property as an invariant of the ODE directly [LICS'18]
    * G |- P    P |- [x'=f(x)&Q]P
    * ---------------------------
    * G |- [x'=f(x)&Q]P
    * (Default behavior: fast (but incomplete) version, no solving attempted)
    * @see André Platzer and Yong Kiam Tan. [[https://doi.org/10.1145/3209108.3209147 Differential equation axiomatization: The impressive power of differential ghosts]]. In Anuj Dawar and Erich Grädel, editors, Proceedings of the 33rd Annual ACM/IEEE Symposium on Logic in Computer Science, LICS'18, pp. 819-828. ACM 2018.
    * @see [[odeInvariantComplete]]
    **/
  lazy val odeInvariant: DependentPositionTactic = DifferentialTactics.odeInvariant(tryHard = false)

  /** Same as odeInvariant but directly reports an error when it detects that the postcondition should be invariant
    * but is currently unprovable.
    * @see [[odeInvariant]]
    */
  @Tactic("ODE Invariant Complete", codeName = "odeInvC",
    longDisplayName = "ODE Invariant Complete",
    premises = "*",
    conclusion = "Γ, P |- [x'=f(x)&Q]P",
    displayLevel="browse")
  lazy val odeInvariantComplete: DependentPositionTactic = DifferentialTactics.odeInvariantComplete

  // more

  /** DG/DA differential ghosts that are generated automatically to prove differential equations.
    *
    * @see [[dG]] */
  @Tactic(longDisplayName = "Auto Differential Ghost")
  lazy val DGauto: DependentPositionTactic = DifferentialTactics.DGauto

  @Tactic(
    longDisplayName = "Differential Conditional Cut",
    premises = "Γ |- [x'=f(x)&R&P]Q ;; Γ, R, !P |- [x'=f(x)&R]!P",
    conclusion = "Γ |- [x'=f(x)&R](P -> Q)"
  )
  val dCC: DependentPositionTactic = DifferentialTactics.dCC

  // more

  /** Prove the given cut formula to hold for the modality at position and turn postcondition into cut->post
    * The operational effect of {a;}*@invariant(f1,f2,f3) is postCut(f1) & postcut(f2) & postCut(f3).
    * {{{
    *   cutShowLbl:     cutUseLbl:
    *   G |- [a]C, D    G |- [a](C->B), D
    *   ---------------------------------
    *          G |- [a]B, D
    * }}}
    *
    * @example {{{
    *   cutShowLbl:      cutUseLbl:
    *   |- [x:=2;]x>1    |- [x:=2;](x>1 -> [y:=x;]y>1)
    *   -----------------------------------------------postCut("x>1".asFormula)(1)
    *   |- [x:=2;][y:=x;]y>1
    * }}}
    * @example {{{
    *   cutShowLbl:      cutUseLbl:
    *   |- [x:=2;]x>1    |- a=2 -> [z:=3;][x:=2;](x>1 -> [y:=x;]y>1)
    *   -------------------------------------------------------------postCut("x>1".asFormula)(1, 1::1::Nil)
    *   |- a=2 -> [z:=3;][x:=2;][y:=x;]y>1
    * }}}
    */
  def postCut(cut: Formula)   : DependentPositionTactic = DLBySubst.postCut(cut)


  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // closing rules

  /** QE: Quantifier Elimination to decide real arithmetic (after simplifying logical transformations).
    * Applies simplifying transformations to the real arithmetic questions before solving it via [[RCF]].
    * @param order the order of variables to use during quantifier elimination
    * @see [[QE]]
    * @see [[RCF]] */
  def QE(order: List[Variable] = Nil, tool: Option[String] = None, timeout: Option[Int] = None): BelleExpr = {
    if (order.isEmpty) QEX(tool, timeout.map(Number(_)))
    else ToolTactics.timeoutQE(order, tool, timeout) // non-serializable for now
  }

  @Tactic("QE", codeName = "QE", revealInternalSteps = true)
  def QEX(tool: Option[String], timeout: Option[Number]): InputTactic = inputanon {
    (tool, timeout) match {
      case (Some(toolName), Some(time)) => ToolTactics.timeoutQE(Nil, Some(toolName), Some(time.value.toInt))
      case (Some(toolName), None) if Try(Integer.parseInt(toolName)).isSuccess => ToolTactics.timeoutQE(Nil, None, Some(Integer.parseInt(toolName)))
      case (Some(toolName), _) =>  ToolTactics.timeoutQE(Nil, Some(toolName))
      case (_, Some(time)) => ToolTactics.timeoutQE(Nil, None, Some(time.value.toInt))
      case (_, _) => ToolTactics.timeoutQE(Nil, None, None)
    }
  }
  lazy val QE: BelleExpr = QEX(None, None)

  /** Quantifier elimination returning equivalent result, irrespective of result being valid or not.
    * Performs QE and allows the goal to be reduced to something that isn't necessarily true.
    * @note You probably want to use fullQE most of the time, because partialQE will destroy the structure of the sequent
    */
  @Tactic(names="Partial QE",
    codeName="pQE",
    longDisplayName = "Partial QE",
    premises="Γ |- Δ",
    //    pQE -----------
    conclusion="Γ<sub>FOLR∀∃</sub> |- Δ<sub>FOLR∀∃</sub>",
    displayLevel="browse")
  def partialQE: BelleExpr = ToolTactics.partialQE(ToolProvider.qeTool().getOrElse(throw new ProverSetupException("partialQE requires a QETool, but got None")))

  /** Splits propositional into many smallest possible QE calls.
    * @param split Configures how the tactic splits into smaller subgoals before QE (default: exhaustive alpha and beta rules).
    * @param preQE Tactic to execute before each individual QE call (default: skip).
    * @param qe How to QE
    */
  def atomicQE(split: BelleExpr = SaturateTactic(onAll(alphaRule | betaRule)), preQE: BelleExpr = skip, qe: BelleExpr = QE): BelleExpr =
    split & onAll(preQE & qe & done)
  def atomicQE: BelleExpr = atomicQE()

  def heuQE: BelleExpr = ToolTactics.heuristicQE(ToolProvider.qeTool().getOrElse(throw new ProverSetupException("QE requires a QETool, but got None")))
  def heuQEPO (po:Ordering[Variable]): BelleExpr = ToolTactics.heuristicQE(ToolProvider.qeTool().getOrElse(throw new ProverSetupException("QE requires a QETool, but got None")),po)

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Bigger Tactics.
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // Utility Tactics
  /** done: check that the current goal is proved and fail if it isn't.
    * @see [[skip]] */
  val done: BelleExpr = DebuggingTactics.done


  /** abbrv(name) Abbreviate the term at the given position by a new name and use that name at all occurrences of that term.
    * @example {{{
    *   maxcd = max(c,d) |- a+b <= maxcd+e
    *   ----------------------------------------abbrv(Variable("maxcd"))(1, 1::0::Nil)
    *                    |- a+b <= max(c, d) + e
    * }}}
    * @param name The new variable to use as an abbreviation.
    * */
  def abbrv(name: Variable): DependentPositionTactic = EqualityTactics.abbrv(name)

  /** abbrv(e, x) Abbreviate term `e` to name `x` (new name if omitted) and use that name at all occurrences of that term. */
  @Tactic(
    names = "abbrv",
    codeName = "abbrv", //@todo name clash with abbrv above (response from BB: not a name clash if only one is annotated. This
    longDisplayName = "Abbreviate",
    // appears to be correct because it maintains backwards-compatibility)
    premises = "Γ(x), x=e |- Δ(x)",
    conclusion = "Γ(e) |- Δ(e)",
    inputs = "e:term;;x[x]:option[variable]",
    revealInternalSteps = true
  )
  def abbrvAll(e: Term, x: Option[Variable]): InputTactic = inputanon { EqualityTactics.abbrv(e, x) }

  /** Rewrites free occurrences of the left-hand side of an equality into the right-hand side at a specific position.
    * @example {{{
    *    x=0 |- 0*y=0, x+1>0
    *    ---------------------eqL2R(-1)(1)
    *    x=0 |- x*y=0, x+1>0
    * }}}
    * @param eqPos The position of the equality. If it points to a formula, it rewrites all occurrences of left in that formula.
    *              If it points to a specific term, then only this term is rewritten.
    */
  //@todo missing AxiomInfo for tactic extraction
  def eqL2R(eqPos: Int): DependentPositionTactic = EqualityTactics.eqL2R(eqPos)
  def eqL2R(eqPos: AntePosition): DependentPositionTactic = EqualityTactics.eqL2R(eqPos)
  /** eXternal wrapper for eqL2R */
  @Tactic(codeName = "L2R", longDisplayName = "Apply Equality", conclusion = "Γ, x=y, P(x) |- Q(x), Δ",
    premises = "Γ, x=y, P(y) |- Q(y), Δ")
  val eqL2RX: DependentTwoPositionTactic = anon ({(pr:ProvableSig, ante: Position, pos: Position) => {
    if (!pos.isAnte)
      throw new IllegalArgumentException("First positional argument to eqL2R must be antecedent")
    eqL2R(ante.checkAnte)(pos)
  }})

  /** Rewrites free occurrences of the right-hand side of an equality into the left-hand side at a specific position ([[EqualityTactics.eqR2L]]). */
  def eqR2L(eqPos: Int): DependentPositionTactic = EqualityTactics.eqR2L(eqPos)
  def eqR2L(eqPos: AntePosition): DependentPositionTactic = EqualityTactics.eqR2L(eqPos)
  /** Rewrites free occurrences of the left-hand side of an equality into the right-hand side exhaustively ([[EqualityTactics.exhaustiveEqL2R]]). */
  @Tactic(names = "L=R all", codeName = "allL2R", longDisplayName = "Apply All Equalities", revealInternalSteps = true)
  val exhaustiveEqL2R: DependentPositionTactic = anon { pos: Position => exhaustiveEqL2R(false)(pos) }
  def exhaustiveEqL2R(hide: Boolean = false): DependentPositionTactic =
    if (hide) anon ((pos: Position, sequent: Sequent) => sequent.sub(pos) match {
      case Some(fml@Equal(l, _)) =>
        val lvars = StaticSemantics.freeVars(l)
        EqualityTactics.exhaustiveEqL2R(pos) &
          Idioms.doIf(_.subgoals.forall(s => StaticSemantics.freeVars(s.without(pos.checkTop)).intersect(lvars).isEmpty))(hideL(pos, fml))
      case Some(e) => throw new TacticInapplicableFailure("Expected equality l=r, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos.prettyString + " is undefined in " + sequent.prettyString)
    })
    else EqualityTactics.exhaustiveEqL2R
  /** Rewrites free occurrences of the right-hand side of an equality into the left-hand side exhaustively ([[EqualityTactics.exhaustiveEqR2L]]). */
  @Tactic(names = "R=L all", codeName = "allR2L", longDisplayName = "Apply All Equalities Inverse", revealInternalSteps = true)
  val exhaustiveEqR2L: DependentPositionTactic = anon { pos: Position => exhaustiveEqR2L(false)(pos) }
  def exhaustiveEqR2L(hide: Boolean = false): DependentPositionTactic =
    if (hide) anon ((pos: Position, sequent: Sequent) => sequent.sub(pos) match {
      case Some(fml@Equal(_, r)) =>
        val rvars = StaticSemantics.freeVars(r)
        EqualityTactics.exhaustiveEqR2L(pos) &
          Idioms.doIf(_.subgoals.forall(s => StaticSemantics.freeVars(s.without(pos.checkTop)).intersect(rvars).isEmpty))(hideL(pos, fml))
      case Some(e) => throw new TacticInapplicableFailure("Expected equality l=r, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos.prettyString + " is undefined in " + sequent.prettyString)
    })
    else EqualityTactics.exhaustiveEqR2L

  /** Transform an FOL formula or term into the formula/term 'to'.
    * A proof why that tranformation is acceptable will be shown on demand.
    * Transforms the FOL formula or term at position 'pos' into the formula/term 'to'. Uses QE to prove the transformation correct.
    * @example {{{
    *                           *
    *                           --------------
    *           a<b |- a<b      |- a<b -> b>a
    *           ------------------------------ transform("a<b".asFormula)(1)
    *           a<b |- b>a
    * }}}
    * @example {{{
    *                                         *
    *                                    ---------------------
    *           a+b<c, b>=0 |- a+b<c     b>=0 |- a+b<c -> a<c
    *           ---------------------------------------------- transform("a+b<c".asFormula)(1)
    *           a+b<c, b>=0 |- a<c
    * }}}
    * @example {{{
    *                           *
    *                           ---------------
    *           a<c |- a<c      |- c+0=c
    *           -------------------------------transform("c".asFormula)(1, 1::Nil)
    *           a<c |- a<c+0
    * }}}
    * @param Q The transformed formula or term that is desired as the result of this transformation.
    */
  @Tactic("trafo", longDisplayName = "Transform Expression", conclusion = "Γ |- P, Δ", premises = "Γ |- Q, Δ"
    /* revealInternalSteps = true not yet possible since non-serializable ProvableSig input used internally */)
  def transform(Q: Expression): DependentPositionWithAppliedInputTactic = inputanon { (pos: Position) => ToolTactics.transform(Q)(pos) }

  /** Determines difference between expression at position and expression `to` and turns diff.
    * into transformations and abbreviations. */
  @Tactic("edit", longDisplayName = "Edit Expression", revealInternalSteps = true)
  def edit(to: Expression): DependentPositionWithAppliedInputTactic = inputanon { (pos: Position) => ToolTactics.edit(to)(pos) }

  //
  /** OnAll(e) == <(e, ..., e) runs tactic `e` on all current branches. */
  def onAll(e: BelleExpr): BelleExpr = OnAll(e)

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Contract Tactics and Debugging Tactics
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // Tactic contracts

  /** Assert that the given condition holds for the goal's sequent. */
  def assertT(cond : Sequent=>Boolean, msg: => String): BelleExpr = DebuggingTactics.assert(cond, msg)
  /** Assert that the sequent has the specified number of antecedent and succedent formulas, respectively. */
  def assertT(antecedents: Int, succedents: Int, msg: => String = ""): BelleExpr = DebuggingTactics.assert(antecedents, succedents, msg)

  // PositionTactic contracts
  /** Assert that the given condition holds for the sequent at the position where the tactic is applied */
  def assertT(cond : (Sequent,Position)=>Boolean, msg: => String): BuiltInPositionTactic = DebuggingTactics.assert(cond, msg)
  /** Assert that the given expression is present at the position in the sequent where this tactic is applied to. */
  def assertE(expected: => Expression, msg: => String): DependentPositionWithAppliedInputTactic = DebuggingTactics.assertE(expected, msg)

  /** errorT raises an error upon executing this tactic, stopping processing */
  def errorT(msg: => String): BuiltInTactic = DebuggingTactics.error(msg)

  /** debug(s) sprinkles debug message s into the output and the ProofNode information */
  def debug(s: => Any): BuiltInTactic = DebuggingTactics.debug(s.toString)
  /** debugAt(s) sprinkles debug message s into the output and the ProofNode information */
  def debugAt(s: => Any): BuiltInPositionTactic = DebuggingTactics.debugAt(s.toString)

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Special functions
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  /** Expands absolute value using `abs(x)=y <-> (x>=0&y=x | x<=0&y=-x)`, see [[EqualityTactics.abs]] */
  lazy val abs: DependentPositionTactic = EqualityTactics.abs
  /** Expands minimum function using `min(x,y)=z <-> (x<=y&z=x | x>=y&z=y)`, see [[EqualityTactics.minmax]] */
  lazy val min: DependentPositionTactic = EqualityTactics.minmax
  /** Expands maximum function using `max(x,y)=z <-> (x>=y&z=x | x<=y&z=y)`, see [[EqualityTactics.minmax]] */
  lazy val max: DependentPositionTactic = EqualityTactics.minmax

  /** Alpha rules are propositional rules that do not split */
  lazy val alphaRule: BelleExpr = andL('_) |
    (orR('_) |
      (implyR('_) |
        (notL('_) |
          notR('_)
          )
        )
      )

  /** Beta rules are propositional rules that split */
  lazy val betaRule: BelleExpr = andR('_) |
    (orL('_) |
      (implyL('_) |
        (equivL('_) |
          equivR('_)
          )
        )
      )

  /** Real-closed field arithmetic on a single formula without any extra smarts and simplifications.
    * @see [[QE]] */
  @Tactic(names="RCF",
    codeName="rcf",
    premises="*",
    //    pQE -----------
    conclusion="Γ<sub>rcf</sub> |- Δ<sub>rcf</sub>",
    displayLevel="browse")
  def RCF: BelleExpr = anon { ToolTactics.rcf(ToolProvider.qeTool().getOrElse(throw new ProverSetupException("RCF requires a QETool, but got None")))}

//  /** Lazy Quantifier Elimination after decomposing the logic in smart ways */
//  //@todo ideally this should be ?RCF so only do anything of RCF if it all succeeds with true
//  def lazyQE = (
//    ((alphaRule | allR('_) | existsL('_)
//          | close
//          //@todo eqLeft|eqRight for equality rewriting directionally toward easy
//          //| (la(TacticLibrary.eqThenHideIfChanged)*)
//          | betaRule)*)
//      | QE)



  // Global Utility Functions

  /**
    * The position of `here()` in the formula `fml`.
    * @return The term or formula position where `here()` occurs in `fml`.
    * @throws IllegalArgumentException if `here()` does not occur in `fml`.
    * @example {{{
    *    positionOf("p() & x>2 -> here() | x=y^2".asFormula) == PosInExpr(1::0::Nil)
    *    positionOf("p() & here() -> x=1 | x=y^2".asFormula) == PosInExpr(0::1::Nil)
    *    positionOf("p() & x>2 -> x=1 | here()=y^2".asFormula) == PosInExpr(1::1::0::Nil)
    *    positionOf("p() & x>2 -> x=1 | x=here()^2".asFormula) == PosInExpr(1::1::1::0::Nil)
    *    positionOf("_ & here() -> _ | _".asFormula) == PosInExpr(0::1::Nil)
    *    positionOf("_ & _ -> _ | .=here()^2".asFormula) == PosInExpr(1::1::1::0::Nil)
    *    positionOf("_ & here() -> _".asFormula) == PosInExpr(0::1::Nil)
    * }}}
    */
  def positionOf(fml: Formula): PosInExpr = fml.find(e =>
    e==FuncOf(Function("here",None,Unit,Real),Nothing) || e==PredOf(Function("here",None,Unit,Bool),Nothing)
  ) match {
    case Some((pos,_)) => pos
    case None => throw new IllegalArgumentException("here() locator does not occur in positionOf(" + fml + ")")
  }

  /** ifThenElse(cond,thenT,elseT) runs `thenT` if `cond` holds at position and `elseT` otherwise. */
  def ifThenElse(cond: (Sequent, Position)=>Boolean, thenT: BelleExpr, elseT: BelleExpr): DependentPositionTactic = anon ((pos:Position,seq:Sequent) =>
    if (cond(seq, pos))
      thenT
    else
      elseT
    )

  /**
    * Prove the new goal by the given tactic, returning the resulting Provable
    *
    * @see [[SequentialInterpreter]]
    * @see [[TactixLibrary.by(Provable)]]
    * @see [[proveBy()]]
    */
  def proveBy(goal: ProvableSig, tactic: BelleExpr): ProvableSig = {
    val v = BelleProvable(goal)
    BelleInterpreter(tactic, v) match {
      case BelleProvable(provable, _) => provable
//      //@note there is no other case at the moment
//      case r => throw BelleIllFormedError("Error in proveBy, goal\n" + goal + " was not provable but instead resulted in\n" + r)
    }
  }

  /**
   * Prove the new goal by the given tactic, returning the resulting Provable
    *
   * @see [[SequentialInterpreter]]
   * @see [[TactixLibrary.by(Provable)]]
   * @see [[proveBy()]]
   * @example {{{
   *   import StringConverter._
   *   import TactixLibrary._
   *   val proof = TactixLibrary.proveBy(Sequent(IndexedSeq(), IndexedSeq("(p()|q()->r()) <-> (p()->r())&(q()->r())".asFormula)), prop)
   * }}}
   */
  def proveBy(goal: Sequent, tactic: BelleExpr): ProvableSig = proveBy(ProvableSig.startProof(goal), tactic)
  /**
   * Prove the new goal by the given tactic, returning the resulting Provable
    *
   * @see [[TactixLibrary.by(Provable)]]
   * @example {{{
   *   import StringConverter._
   *   import TactixLibrary._
   *   val proof = TactixLibrary.proveBy("(p()|q()->r()) <-> (p()->r())&(q()->r())".asFormula, prop)
   * }}}
   */
  def proveBy(goal: Formula, tactic: BelleExpr): ProvableSig = proveBy(Sequent(IndexedSeq(), IndexedSeq(goal)), tactic)

  @Tactic("useLemma", codeName = "useLemma", longDisplayName = "Use Lemma")
  def useLemmaX (lemma: String, tactic: Option[String]): InputTactic = inputanon { TactixLibrary.useLemma(lemma, tactic.map(BelleParser)) }

  /** useLemma(lemmaName, tactic) applies the lemma identified by `lemmaName`, optionally adapting the lemma formula to
    * the current subgoal using the tactic `adapt`. Literal lemma application if `adapt` is None. */
  def useLemma(lemmaName: String, adapt: Option[BelleExpr]): BelleExpr =
    anon { (_: Sequent) =>
      val userLemmaName = "user" + File.separator + lemmaName //@todo FileLemmaDB + multi-user environment
      if (LemmaDBFactory.lemmaDB.contains(userLemmaName)) {
        val lemma = LemmaDBFactory.lemmaDB.get(userLemmaName).get
        useLemma(lemma, adapt)
      } else label(BelleLabels.lemmaUnproved(lemmaName))
    }
  /** useLemma(lemma, tactic) applies the `lemma`, optionally adapting the lemma formula to
    * the current subgoal using the tactic `adapt`. Literal lemma application if `adapt` is None. */
  def useLemma(lemma: Lemma, adapt: Option[BelleExpr]): BelleExpr = anon { (_: Sequent) =>
    adapt match {
      case Some(t) =>
        val substs = lemma.evidence.flatMap({
          case ToolEvidence(info) =>
            import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
            val tactic = info.find(_._1 == "tactic").map(_._2)
            val model = info.find(_._1 == "model")
            (model, tactic) match {
              case (Some(model), Some(tactic)) =>
                val defs = ArchiveParser.parser(model._2).head.defs
                if (tactic.contains("expandAllDefs")) {
                  defs.substs
                } else {
                  val subst1 = """US\("([^"]+)"\)""".r("subst").findAllMatchIn(tactic).map(_.group("subst").asSubstitutionPair).toList
                  val expandedSymbols = """expand "([^"]+)"""".r("symbol").findAllMatchIn(tactic).map(_.group("symbol")).toList
                  subst1 ++ defs.substs.filter({
                    case SubstitutionPair(FuncOf(fn, _), _) => expandedSymbols.contains(fn.prettyString)
                    case SubstitutionPair(PredOf(fn, _), _) => expandedSymbols.contains(fn.prettyString)
                    case SubstitutionPair(PredicationalOf(fn, _), _) =>  expandedSymbols.contains(fn.prettyString)
                    case _ => false
                  })
                }
              case (None, Some(tactic)) =>
                """US\("([^"]+)"\)""".r("subst").findAllMatchIn(tactic).map(_.group("subst").asSubstitutionPair).toList
              case (_, None) =>
                //@todo unify sequent with lemma conclusion
                Nil
            }
          case _ => Nil
        })
        ExpandAll(substs) & cut(lemma.fact.conclusion.toFormula) <(t, cohideR('Rlast) &
          (if (lemma.fact.conclusion.ante.nonEmpty) implyR(1) & andL('Llast)*(lemma.fact.conclusion.ante.size-1)
           else /* toFormula returns true->conclusion */ implyR(1) & hideL('Llast)) &
          (if (lemma.fact.conclusion.succ.nonEmpty) orR('Rlast)*(lemma.fact.conclusion.succ.size-1)
           else /* toFormula returns conclusion->false */ hideR(1)) &
          by(lemma))
      case None => by(lemma)
    }
  }

  /** Applies the lemma by matching `key` in the lemma with the tactic position. */
  @Tactic("useLemmaAt", longDisplayName = "Use Lemma at Position")
  def useLemmaAt(lemma: String, key: Option[PosInExpr]): DependentPositionWithAppliedInputTactic = inputanon (
    (pos: Position, _: Sequent) => {
      val userLemmaName = "user" + File.separator + lemma //@todo FileLemmaDB + multi-user environment
      if (LemmaDBFactory.lemmaDB.contains(userLemmaName)) {
        val lem = LemmaDBFactory.lemmaDB.get(userLemmaName).get
        useAt(lem, key.getOrElse(if (pos.isAnte) PosInExpr(0::Nil) else PosInExpr(1::Nil)))(pos)
      } else throw new BelleAbort("Missing lemma " + lemma, "Please prove lemma " + lemma + " first")
    })

  /** Finds a counter example, indicating that the specified formula is not valid. */
  def findCounterExample(formula: Formula): Option[Map[NamedSymbol, Expression]] = ToolProvider.cexTool().getOrElse(throw new ProverSetupException("findCounterExample requires a CounterExampleTool, but got None")).findCounterExample(formula)

  ///

  /** Axiom and tactic index for [[TactixLibrary.step]]
    * @param isAnte true if occurs at top-level in antecedent, false if top-level in succedent
    * @param expr the expression for which a canonical tactic step is sought.
    * @see [[AxIndex]] */
  private def sequentStepIndex(isAnte: Boolean)(expr: Expression): Option[DerivationInfo] = (expr, isAnte) match {
    case (True, false) => Some(TacticInfo("closeTrue"))
    case (False, true) => Some(TacticInfo("closeFalse"))
    // prefer simplification over left-right-swaps
    case (Not(Box(_,Not(_))), _) => Some(Ax.diamond)
    case (Not(Diamond(_,Not(_))), _) => Some(Ax.box)
    case (_: Not, true) => Some(TacticInfo("notL"))
    case (_: Not, false) => Some(TacticInfo("notR"))
    case (_: And, true) => Some(TacticInfo("andL"))
    case (_: And, false) => Some(TacticInfo("andR"))
    case (_: Or, true) => Some(TacticInfo("orL"))
    case (_: Or, false) => Some(TacticInfo("orR"))
    case (_: Imply, true) => Some(TacticInfo("implyL"))
    case (_: Imply, false) => Some(TacticInfo("implyR"))
    case (_: Equiv, true) => Some(TacticInfo("equivL"))
    case (_: Equiv, false) => Some(TacticInfo("equivR"))
    case (_: Forall, true) => Some(TacticInfo("allL"))
    case (_: Forall, false) => Some(TacticInfo("allR"))
    case (_: Exists, true) => Some(TacticInfo("existsL"))
    case (_: Exists, false) => Some(TacticInfo("existsR"))
    case _ => AxIndex.axiomFor(expr) /* @note same as HilbertCalculus.stepAt(pos) */
  }

  /** Solve an arithmetic goal with sum-of-squares */
  @Tactic("sossolve", longDisplayName = "Solve with sum-of-squares witness",
    premises="1 + g<sub>1</sub><sup>2</sup>+ ... + g<sub>n</sub><sup>2</sup> = 0",
    //    sossolve -----------
    conclusion="Γ<sub>FOLR∃</sub> |- Δ<sub>FOLR∀</sub>",
    displayLevel="all")
  val sossolve : BelleExpr = anon ( SOSSolve.sos() )
}
