package edu.cmu.cs.ls.keymaerax.btactics

import edu.cmu.cs.ls.keymaerax.Configuration
import edu.cmu.cs.ls.keymaerax.bellerophon._
import edu.cmu.cs.ls.keymaerax.btactics.AnonymousLemmas._
import edu.cmu.cs.ls.keymaerax.infrastruct.Augmentors._
import edu.cmu.cs.ls.keymaerax.infrastruct.ExpressionTraversal.ExpressionTraversalFunction
import edu.cmu.cs.ls.keymaerax.btactics.PropositionalTactics.toSingleFormula
import edu.cmu.cs.ls.keymaerax.btactics.TactixLibrary._
import edu.cmu.cs.ls.keymaerax.btactics.TacticFactory._
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.infrastruct._
import edu.cmu.cs.ls.keymaerax.btactics.macros.Tactic
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import edu.cmu.cs.ls.keymaerax.pt.ProvableSig
import edu.cmu.cs.ls.keymaerax.tools.{MathematicaComputationAbortedException, MathematicaInapplicableMethodException, SMTQeException, SMTTimeoutException, ToolOperationManagement}
import edu.cmu.cs.ls.keymaerax.tools.ext.QETacticTool
import edu.cmu.cs.ls.keymaerax.tools.install.ToolConfiguration

import scala.annotation.tailrec
import scala.math.Ordering.Implicits._
import scala.collection.immutable._
import scala.util.{Failure, Success, Try}

/**
 * Implementation: Tactics that execute and use the output of tools.
 * Also contains tactics for pre-processing sequents.
 * @author Nathan Fulton
 * @author Stefan Mitsch
 */
private object ToolTactics {

  private val namespace = "tooltactics"

  @Tactic("useSolver", codeName = "useSolver")
  // NB: anon (Sequent) is necessary even though argument "seq" is not referenced:
  // this ensures that TacticInfo initialization routine can initialize byUSX without executing the body
  def switchSolver(tool: String): InputTactic = inputanon { _: Sequent => {
    val config = ToolConfiguration.config(tool)
    tool.toLowerCase match {
      case "mathematica" =>
        ToolProvider.setProvider(MultiToolProvider(MathematicaToolProvider(config) :: Z3ToolProvider() :: Nil))
      case "wolframengine" =>
        Configuration.set(Configuration.Keys.MATH_LINK_TCPIP, "true", saveToFile = false)
        ToolProvider.setProvider(MultiToolProvider(WolframEngineToolProvider(config) :: Z3ToolProvider() :: Nil))
      case "z3" => ToolProvider.setProvider(new Z3ToolProvider)
      case _ => throw new InputFormatFailure("Unknown tool " + tool + "; please use one of mathematica|wolframengine|z3")
    }
    nil
  }}

  /** Assert that there is no counter example. skip if none, error if there is. */
  // was  "assertNoCEX"
  lazy val assertNoCex: BelleExpr = anon ((sequent: Sequent) => {
    Try(findCounterExample(sequent.toFormula)) match {
      case Success(Some(cex)) => throw BelleCEX("Counterexample", cex, sequent)
      case Success(None) => skip
      case Failure(_: ProverSetupException) => skip //@note no counterexample tool, so no counterexample
      case Failure(_: MathematicaComputationAbortedException) => skip
      case Failure(ex) => throw ex //@note fail with all other exceptions
    }
  })

  /** Performs QE and fails if the goal isn't closed. */
  def fullQE(order: List[Variable] = Nil)(qeTool: => QETacticTool): BelleExpr = anon { seq: Sequent =>
    if (!seq.isFOL) throw new TacticInapplicableFailure("QE is applicable only on arithmetic questions, but got\n" +
      seq.prettyString + "\nPlease apply additional proof steps to hybrid programs first.")

    val doRcf = rcf(qeTool)

    val closure = toSingleFormula & FOQuantifierTactics.universalClosure(order)(1)

    val convertInterpretedSymbols = Configuration.getBoolean(Configuration.Keys.QE_ALLOW_INTERPRETED_FNS).getOrElse(false)
    val expand =
      if (convertInterpretedSymbols) skip
      else EqualityTactics.expandAll &
        assertT(s => !StaticSemantics.symbols(s).exists({ case Function(_, _, _, _, interpreted) => interpreted case _ => false }),
          "Aborting QE since not all interpreted functions are expanded; please click 'Edit' and enclose interpreted functions with 'expand(.)', e.g. x!=0 -> expand(abs(x))>0.")

    val plainQESteps =
      if (convertInterpretedSymbols) (closure & doRcf) :: (EqualityTactics.expandAll & closure & doRcf) :: Nil
      else (closure & doRcf) :: Nil // expanded already

    val plainQE = plainQESteps.reduce[BelleExpr](_ | _)

    //@note don't split exhaustively (may explode), but *3 is only a guess
    val splittingQE =
      ArithmeticSimplification.smartHide & onAll(Idioms.?(orL('L) | andR('R)))*3 & onAll(plainQE & done)

    val doQE = EqualityTactics.applyEqualities & hideTrivialFormulas & expand & (TimeoutAlternatives(plainQESteps, 5000) | splittingQE | plainQE)

    AnonymousLemmas.cacheTacticResult(
      Idioms.doIf(p => !p.isProved && p.subgoals.forall(_.isFOL))(
        assertT(_.isFOL, "QE on FOL only") &
        allTacticChase()(notL, andL, notR, implyR, orR, allR) &
          Idioms.doIf(!_.isProved)(
            close | Idioms.doIfElse(_.subgoals.forall(s => s.isPredicateFreeFOL && s.isFuncFreeArgsFOL))(
              // if
              doQE
              ,
              // else
              hidePredicates & hideQuantifiedFuncArgsFmls &
                assertT((s: Sequent) => s.isPredicateFreeFOL && s.isFuncFreeArgsFOL, "Uninterpreted predicates and uninterpreted functions with bound arguments are not supported; attempted hiding but failed, please apply further manual steps to expand definitions and/or instantiate arguments and/or hide manually") &
                doQE & done
                | anon {(s: Sequent) => throw new TacticInapplicableFailure("The sequent mentions uninterpreted functions or predicates; attempted to prove without but failed. Please apply further manual steps to expand definitions and/or instantiate arguments.")}
            )
          )
        ),
      //@note does not evaluate qeTool since NamedTactic's tactic argument is evaluated lazily
      "qecache/" + qeTool.getClass.getSimpleName
    ) & Idioms.doIf(!_.isProved)(anon ((s: Sequent) =>
      if (s.succ.head == False) label(BelleLabels.QECEX)
      else DebuggingTactics.done("QE was unable to prove: invalid formula"))
    )
  }

  /** @see[[TactixLibrary.QE]] */
  def timeoutQE(order: List[Variable] = Nil, requiresTool: Option[String] = None, timeout: Option[Int] = None): BelleExpr = {
    lazy val tool = ToolProvider.qeTool(requiresTool.map(n => if (n == "M") "Mathematica" else n)).getOrElse(
      throw new ProverSetupException(s"QE requires ${requiresTool.getOrElse("a QETool")}, but got None"))
    lazy val resetTimeout: BelleExpr => BelleExpr = timeout match {
      case Some(t) => tool match {
        case tom: ToolOperationManagement =>
          val oldTimeout = tom.getOperationTimeout
          tom.setOperationTimeout(t)
          if (oldTimeout != t) {
            e: BelleExpr => TryCatch(e, classOf[Throwable],
              // catch: noop
              (_: Throwable) => skip,
              // finally: reset timeout
              Some(anon((p: ProvableSig) => { tom.setOperationTimeout(oldTimeout); p }))
            )
          } else (e: BelleExpr) => e
        case _ => throw new UnsupportedTacticFeature("Tool " + tool + " does not support timeouts")
      }
      case None => (e: BelleExpr) => e
    }
    lazy val timeoutTool: QETacticTool = timeout match {
      case Some(t) => tool match {
        case tom: ToolOperationManagement =>
          tom.setOperationTimeout(t)
          tool
        case _ => throw new UnsupportedTacticFeature("Tool " + tool + " does not support timeouts")
      }
      case None => tool
    }
    resetTimeout(ToolTactics.fullQE(order)(timeoutTool))
  }

  /** Hides duplicate formulas (expensive because needs to sort positions). */
  private val hideDuplicates = anon ((seq: Sequent) => {
    val hidePos = seq.zipWithPositions.map(f => (f._1, f._2.isAnte, f._2)).groupBy(f => (f._1, f._2)).
      filter({ case (_, l) => l.size > 1 })
    val tactics = hidePos.values.flatMap({ case _ :: tail => tail.map(t => (t._3, hide(t._3))) }).toList
    tactics.sortBy({ case (pos, _) => pos.index0 }).map(_._2).reverse.reduceOption[BelleExpr](_&_).getOrElse(skip)
  })

  /** Hides useless trivial true/false formulas. */
  private val hideTrivialFormulas = anon ((seq: Sequent) => {
    val hidePos = seq.zipWithPositions.filter({
      case (True, pos) => pos.isAnte
      case (False, pos) => pos.isSucc
      case (Equal(l, r), pos) => pos.isAnte && l == r
      case (LessEqual(l, r), pos) => pos.isAnte && l == r
      case (GreaterEqual(l, r), pos) => pos.isAnte && l == r
      case (NotEqual(l, r), pos) => pos.isSucc && l == r
      case (Less(l, r), pos) => pos.isSucc && l == r
      case (Greater(l, r), pos) => pos.isSucc && l == r
      case _ => false
    }).map(p => hide(p._2)).reverse
    hidePos.reduceOption[BelleExpr](_&_).getOrElse(skip)
  })

  def fullQE(qeTool: => QETacticTool): BelleExpr = fullQE()(qeTool)

  // Follows heuristic in C.W. Brown. Companion to the tutorial: Cylindrical algebraic decomposition, (ISSAC 2004)
  // www.usna.edu/Users/cs/wcbrown/research/ISSAC04/handout.pdf
  //For each variable, we need to compute:
  // 1) max degree of variable in the sequent
  // 2) max total-degree of terms containing that variable
  // 3) number of terms containing that variable
  // "Terms" ~= "monomials"
  // This isn't accurate for divisions (which is treated as a multiplication)
  // Map[String,(Int,Int,Int)]
  private def addy(p1:(Int,Int)=>Int,p2:(Int,Int)=>Int,p3:(Int,Int)=>Int,l:(Int,Int,Int),r:(Int,Int,Int)) : (Int,Int,Int) = {
    (p1(l._1,r._1), p2(l._2,r._2), p3(l._3,r._3) )
  }

  private def merge(m1:Map[Variable,(Int,Int,Int)],m2:Map[Variable,(Int,Int,Int)],p1:(Int,Int)=>Int,p2:(Int,Int)=>Int,p3:(Int,Int)=>Int) : Map[Variable,(Int,Int,Int)] = {
    val matches = m1.keySet.intersect(m2.keySet)
    val updm1 = matches.foldLeft(m1)( (m,s) => m+(s->addy(p1,p2,p3,m1(s),m2(s))))
    updm1 ++ (m2 -- m1.keySet)
  }

  private def termDegs(t:Term) : Map[Variable,(Int,Int,Int)] = {

    t match {
      case v:Variable => Map((v,(1,1,1)))
      case Neg(tt) => termDegs(tt)
      case Plus(l,r) => merge( termDegs(l),termDegs(r),(x,y)=>math.max(x,y),(x,y)=>math.max(x,y),(x,y)=>x+y)
      case Minus(l,r) => termDegs(Plus(l,r))
      case Times(l,r) =>
        val lm = termDegs(l)
        val lmax = lm.values.map(p=>p._2).foldLeft(0)((l,r)=>math.max(l,r))
        val rm = termDegs(r)
        val rmax = rm.values.map(p=>p._2).foldLeft(0)((l,r)=>math.max(l,r))
        val lmap = lm.mapValues(p=>(p._1,p._2+rmax,p._3) )
        val rmap = rm.mapValues(p=>(p._1,p._2+lmax,p._3) ) //Updated max term degrees
        merge(lmap,rmap,(x,y)=>x+y,(x,y)=>math.max(x,y),(x,y)=>x+y) /* The 3rd one probably isn't correct for something like x*x*x */
      case Divide(l,r) => termDegs(Times(l,r))
      case Power(p,n:Number) =>
        val pm = termDegs(p)
        //Assume integer powers
        pm.mapValues( (p:(Int,Int,Int)) => (p._1*n.value.toInt,p._2*n.value.toInt,p._3) )
      case FuncOf(_,tt) => termDegs(tt)
      case Pair(l,r) => merge(termDegs(l),termDegs(r),(x,y)=>math.max(x,y),(x,y)=>math.max(x,y),(x,y)=>x+y)
      case _ => Map[Variable,(Int,Int,Int)]()
    }
  }

  //This just takes the max or sum where appropriate
  private def fmlDegs(f:Formula) : Map[Variable,(Int,Int,Int)] = {
    f match {
      case b:BinaryCompositeFormula => merge(fmlDegs(b.left),fmlDegs(b.right),(x,y)=>math.max(x,y),(x,y)=>math.max(x,y),(x,y)=>x+y)
      case u:UnaryCompositeFormula => fmlDegs(u.child)
      case f:ComparisonFormula => merge(termDegs(f.left),termDegs(f.right),(x,y)=>math.max(x,y),(x,y)=>math.max(x,y),(x,y)=>x+y)
      case q:Quantified => fmlDegs(q.child) -- q.vars
      case m:Modal => fmlDegs(m.child) //QE wouldn't understand this anyway...
      case _ => Map() //todo: pred symbols?
    }
  }

  /** Syntactic approx. of degree of variable x in term t
    *
    * @param t the term t
    * @param x the variable x to compute the degree
    * @return the degree
    */
  def varDegree(t:Term, x:Variable) : Int = {
    val tx = termDegs(t)
    if(tx.contains(x)) tx(x)._1
    else 0
  }

    private def seqDegs(s:Sequent) : Map[Variable,(Int,Int,Int)] = {
    (s.ante++s.succ).foldLeft(Map[Variable,(Int,Int,Int)]())(
      (m:Map[Variable,(Int,Int,Int)],f:Formula) => merge(m,fmlDegs(f),(x,y)=>math.max(x,y),(x,y)=>math.max(x,y),(x,y)=>x+y))
  }

  private def equalityOrder[T]: Ordering[T] = (_: T, _: T) => 0

  private def orderHeuristic(s:Sequent,po:Ordering[Variable]) : List[Variable] = {
    val m = seqDegs(s)
    val ls = m.keySet.toList.sortWith( (x,y) => {
      val c = po.compare(x,y)
      if (c==0) m(x) < m(y)
      else c < 0
      }
    )
    ls
  }

  private def orderedClosure(po:Ordering[Variable]) = new SingleGoalDependentTactic("ordered closure") {
    override def computeExpr(seq: Sequent): BelleExpr = {
      val order = orderHeuristic(seq,po)
      FOQuantifierTactics.universalClosure(order)(1)
    }
  }

  //Note: the same as fullQE except it uses computes the heuristic order in the middle
  def heuristicQE(qeTool: => QETacticTool, po: Ordering[Variable]=equalityOrder): BelleExpr = {
    require(qeTool != null, "No QE tool available. Use parameter 'qeTool' to provide an instance (e.g., use withMathematica in unit tests)")
    Idioms.NamedTactic("ordered QE",
      //      DebuggingTactics.recordQECall() &
      done | //@note don't fail QE if already proved
        (SaturateTactic(alphaRule) &
        (close |
          (SaturateTactic(EqualityTactics.atomExhaustiveEqL2R('L)) &
          hidePredicates &
          toSingleFormula & orderedClosure(po) & rcf(qeTool) &
            (done | anon ((s: Sequent) =>
              if (s.succ.head == False) label(BelleLabels.QECEX)
              else DebuggingTactics.done("QE was unable to prove: invalid formula"))
              ))))
    )}

  /** Performs QE and allows the goal to be reduced to something that isn't necessarily true.
    * @note You probably want to use fullQE most of the time, because partialQE will destroy the structure of the sequent
    */
  // was "pQE"
  def partialQE(qeTool: => QETacticTool): BelleExpr = anon ((s: Sequent) => {
    // dependent tactic so that qeTool is evaluated only when tactic is executed, but not when tactic is instantiated
    require(qeTool != null, "No QE tool available. Use parameter 'qeTool' to provide an instance (e.g., use withMathematica in unit tests)")
    hidePredicates & toSingleFormula & rcf(qeTool) &
      (if (s.ante.exists(!_.isInstanceOf[PredOf]))
        Idioms.doIf(!_.isProved)(cut(s.ante.filterNot(_.isInstanceOf[PredOf]).reduceRight(And)) <(
          SaturateTactic(andL('L)) & SimplifierV3.fullSimpTac(),
          QE & done
        ))
       else nil)
  })

  /** Performs Quantifier Elimination on a provable containing a single formula with a single succedent. */
  def rcf(qeTool: => QETacticTool): BelleExpr = internal ("_rcf", (sequent: Sequent) => {
    require(qeTool != null, "No QE tool available. Use parameter 'qeTool' to provide an instance (e.g., use withMathematica in unit tests)")
    assert(sequent.ante.isEmpty && sequent.succ.length == 1, "Provable's subgoal should have only a single succedent.")
    require(sequent.succ.head.isFOL, "QE only on FOL formulas")

    //Run QE and extract the resulting provable and equivalence
    //@todo how about storing the lemma, but also need a way of finding it again
    //@todo for storage purposes, store rcf(lemmaName) so that the proof uses the exact same lemma without
    val qeFact = try {
      qeTool.qe(sequent.succ.head).fact
    } catch {
      case ex: SMTQeException => throw new TacticInapplicableFailure(ex.getMessage, ex)
      case ex: SMTTimeoutException => throw new TacticInapplicableFailure(ex.getMessage, ex)
      case ex: MathematicaInapplicableMethodException => throw new TacticInapplicableFailure(ex.getMessage, ex)
    }
    val Equiv(_, result) = qeFact.conclusion.succ.head

    cutLR(result)(1) & Idioms.<(
      /*use*/ closeT | skip,
      /*show*/ equivifyR(1) & commuteEquivR(1) & by(qeFact) & done
    )
  })

  /** @see [[TactixLibrary.transform()]] */
  def transform(to: Expression): DependentPositionTactic = inputanon {(pos: Position, sequent: Sequent) => {
    require(sequent.sub(pos) match {
      case Some(fml: Formula) => fml.isFOL && to.kind == fml.kind
      case Some(t: Term) => to.kind == t.kind
      case _ => false
    }, "transform only on arithmetic formulas and terms")

    to match {
      case f: Formula => transformFormula(f, sequent, pos)
      case t: Term => transformTerm(t, sequent, pos)
      case _ => assert(false, "Precondition already checked that other types cannot occur " + to); ???
    }
  }}

  /** @see [[TactixLibrary.edit()]] */
  def edit(to: Expression): DependentPositionWithAppliedInputTactic = inputanon {(pos: Position, sequent: Sequent) => {
    sequent.sub(pos) match {
      case Some(e) if e.kind != to.kind => throw new TacticInapplicableFailure("edit only applicable to terms or formulas of same kind, but " + e.prettyString + " of kind " + e.kind + " is not " + to.kind)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
      case _ => // ok
    }

    val (abbrvTo: Expression, abbrvTactic: BelleExpr) = createAbbrvTactic(to, sequent)
    val (expandTo: Expression, expandTactic: BelleExpr) = createExpandTactic(abbrvTo, sequent, pos)

    val transformTactic = anon (sequent.sub(pos) match {
      case Some(e) =>
        try {
          //@note skip transformation if diff is abbreviations only (better performance on large formulas)
          //@todo find specific transform position based on diff (needs unification for terms like 2+3, 5)
          val diff = UnificationMatch(to, e)
          if (diff.usubst.subsDefsInput.nonEmpty && diff.usubst.subsDefsInput.forall(_.what match {
            case FuncOf(Function(name, None, _, _, _), _) => name == "abbrv" || name == "expand"
            case _ => false
          })) skip
          else TactixLibrary.transform(expandTo)(pos) & DebuggingTactics.assertE(expandTo, "Unexpected edit result", new TacticInapplicableFailure(_))(pos)
        } catch {
          case ex: UnificationException =>
            //@note looks for specific transform position until we have better formula diff
            //@note Exception reports variable unifications and function symbol unifications swapped
            if (ex.input.asExpr.isInstanceOf[FuncOf] && !ex.shape.asExpr.isInstanceOf[FuncOf]) {
              FormulaTools.posOf(e, ex.shape.asExpr) match {
                case Some(pp) =>
                  TactixLibrary.transform(ex.input.asExpr)(pos.topLevel ++ pp) &
                    DebuggingTactics.assertE(expandTo, "Unexpected edit result", new TacticInapplicableFailure(_))(pos) |
                  TactixLibrary.transform(expandTo)(pos) &
                    DebuggingTactics.assertE(expandTo, "Unexpected edit result", new TacticInapplicableFailure(_))(pos)
                case _ =>
                  TactixLibrary.transform(expandTo)(pos) &
                    DebuggingTactics.assertE(expandTo, "Unexpected edit result", new TacticInapplicableFailure(_))(pos)
              }
            } else {
              FormulaTools.posOf(e, ex.input.asExpr) match {
                case Some(pp) =>
                  TactixLibrary.transform(ex.shape.asExpr)(pos.topLevel ++ pp) &
                    DebuggingTactics.assertE(expandTo, "Unexpected edit result", new TacticInapplicableFailure(_))(pos) |
                  TactixLibrary.transform(expandTo)(pos) &
                    DebuggingTactics.assertE(expandTo, "Unexpected edit result", new TacticInapplicableFailure(_))(pos)
                case _ =>
                  TactixLibrary.transform(expandTo)(pos) &
                    DebuggingTactics.assertE(expandTo, "Unexpected edit result", new TacticInapplicableFailure(_))(pos)
              }
            }
        }
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
    })

    abbrvTactic & expandTactic & transformTactic
  }}

  /** Parses `to` for occurrences of `abbrv` to create a tactic. Returns `to` with `abbrv(...)` replaced by the
    * abbreviations and the tactic to turn `to` into the returned expression by proof. */
  private def createAbbrvTactic(to: Expression, sequent: Sequent): (Expression, BelleExpr) = {
    var nextAbbrvName: Variable = TacticHelper.freshNamedSymbol(Variable("abbrv"), sequent)
    val abbrvs = scala.collection.mutable.Map[PosInExpr, Term]()

    val traverseFn = new ExpressionTraversalFunction() {
      override def preT(p: PosInExpr, e: Term): Either[Option[ExpressionTraversal.StopTraversal], Term] = e match {
        case FuncOf(Function("abbrv", None, _, _, _), abbrv@Pair(_, v: Variable)) =>
          abbrvs(p) = abbrv
          Right(v)
        case FuncOf(Function("abbrv", None, _, _, _), t) =>
          val abbrv = nextAbbrvName
          nextAbbrvName = Variable(abbrv.name, Some(abbrv.index.getOrElse(-1) + 1))
          abbrvs(p) = Pair(t, abbrv)
          Right(abbrv)
        case _ => Left(None)
      }
    }

    val abbrvTo: Expression =
      if (to.kind == FormulaKind) ExpressionTraversal.traverse(traverseFn, to.asInstanceOf[Formula]).get
      else ExpressionTraversal.traverse(traverseFn, to.asInstanceOf[Term]).get
    //@todo unify to check whether abbrv is valid; may need reassociating, e.g. in x*y*z x*abbrv(y*z)

    val abbrvTactic = abbrvs.values.map({
      case Pair(t, v: Variable) => TactixLibrary.abbrvAll(t, Some(v))
    }).reduceOption[BelleExpr](_ & _).getOrElse(skip)
    (abbrvTo, abbrvTactic)
  }

  /** Parses `to` for occurrences of `expand` to create a tactic. Returns `to` with `expand(fn)` replaced by the
    * variable corresponding to the expanded fn (abs,min,max) together with the tactic to turn `to` into the returned
    * expression by proof. */
  private def createExpandTactic(to: Expression, sequent: Sequent, pos: Position): (Expression, BelleExpr) = {
    val nextName: scala.collection.mutable.Map[String, Variable] = scala.collection.mutable.Map(
        "abs" -> TacticHelper.freshNamedSymbol(Variable("abs"), sequent),
        "min" -> TacticHelper.freshNamedSymbol(Variable("min"), sequent),
        "max" -> TacticHelper.freshNamedSymbol(Variable("max"), sequent))

    val expandedVars = scala.collection.mutable.Map[PosInExpr, String]()

    def getNextName(s: String, p: PosInExpr): Term = {
      val nn = nextName(s)
      nextName.put(s, Variable(nn.name, Some(nn.index.getOrElse(-1) + 1)))
      expandedVars(p) = s
      nn
    }

    val traverseFn = new ExpressionTraversalFunction() {
      override def preT(p: PosInExpr, e: Term): Either[Option[ExpressionTraversal.StopTraversal], Term] = e match {
        case FuncOf(Function("expand", None, _, _, _), t) => t match {
          case FuncOf(Function("abs", _, _, _, _), _) => Right(getNextName("abs", p))
          case FuncOf(Function("min", _, _, _, _), _) => Right(getNextName("min", p))
          case FuncOf(Function("max", _, _, _, _), _) => Right(getNextName("max", p))
        }
        case _ => Left(None)
      }
    }

    val expandTo: Expression =
      if (to.kind == FormulaKind) ExpressionTraversal.traverse(traverseFn, to.asInstanceOf[Formula]).get
      else ExpressionTraversal.traverse(traverseFn, to.asInstanceOf[Term]).get

    val tactic = expandedVars.toIndexedSeq.sortWith((a, b) => a._1.pos < b._1.pos).map({
      case (p, "abs") => EqualityTactics.abs(pos.topLevel ++ p)
      case (p, "min" | "max") => EqualityTactics.minmax(pos.topLevel ++ p)
    }).reduceOption[BelleExpr](_ & _).getOrElse(skip)
    (expandTo, tactic)
  }

  /** Transforms the formula at position `pos` into the formula `to`. */
  private def transformFormula(to: Formula, sequent: Sequent, pos: Position) = {
    val polarity = FormulaTools.polarityAt(sequent(pos.top), pos.inExpr)*(if (pos.isSucc) 1 else -1)

    val (src, tgt) = (sequent.sub(pos), to) match {
      case (Some(src: Formula), tgt: Formula) => if (polarity > 0) (tgt, src) else (src, tgt)
      case (Some(e), _) => throw new TacticInapplicableFailure("transformFormula only applicable to formulas, but got " + e.prettyString)
      case (None, _) => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
    }

    val boundVars = StaticSemantics.boundVars(sequent(pos.top))
    val gaFull =
      if (pos.isSucc) (sequent.ante ++ sequent.succ.patch(pos.top.getIndex, Nil, 1).map(Not)).flatMap(FormulaTools.conjuncts).filter(_.isFOL)
      else (sequent.ante.patch(pos.top.getIndex, Nil, 1) ++ sequent.succ.map(Not)).flatMap(FormulaTools.conjuncts).filter(_.isFOL)

    @tailrec
    def proveFact(assumptions: IndexedSeq[Formula], filters: List[IndexedSeq[Formula]=>IndexedSeq[Formula]]): (ProvableSig, IndexedSeq[Formula]) = {
      val filteredAssumptions = filters.head(assumptions)
      lazy val filteredAssumptionsFml = filteredAssumptions.reduceOption(And).getOrElse(True)
      val pr =
        if (filteredAssumptions.isEmpty) proveBy(Imply(src, tgt), master())
        else if (polarity > 0) proveBy(Imply(And(filteredAssumptionsFml, src), tgt), master())
        else proveBy(Imply(filteredAssumptionsFml, Imply(src, tgt)), master())

      if (pr.isProved || filters.tail.isEmpty) (pr, filteredAssumptions)
      else proveFact(assumptions, filters.tail)
    }
    val (fact, ga) = proveFact(gaFull,
      ( // first try without any assumptions
        (al: IndexedSeq[Formula]) => al.filter(_ => false)) ::
        // then without alternatives to prove and without irrelevant formulas (non-overlapping variables)
        ((al: IndexedSeq[Formula]) => al.filter({ case Not(_) => false case _ => true }).
          filter(StaticSemantics.freeVars(_).intersect(boundVars).isEmpty)) ::
        // then without irrelevant formulas (non-overlapping variables)
        ((al: IndexedSeq[Formula]) => al.filter(StaticSemantics.freeVars(_).intersect(boundVars).isEmpty)) ::
        // then with full sequent
        ((al: IndexedSeq[Formula]) => al.filter(_ => true)) :: Nil)

    def propPushLeftIn(op: (Formula, Formula) => Formula) = {
      val p = "p_()".asFormula
      val q = "q_()".asFormula
      val r = "r_()".asFormula
      proveBy(Imply(op(p, Imply(q, r)), Imply(op(p, q), op(p, r))), prop & done)
    }

    def propPushRightIn(op: (Formula, Formula) => Formula) = {
      val p = "p_()".asFormula
      val q = "q_()".asFormula
      val r = "r_()".asFormula
      proveBy(Imply(op(Imply(q, r), p), Imply(op(q, p), op(r, p))), prop & done)
    }

    lazy val implyFact = remember("q_() -> (p_() -> p_()&q_())".asFormula, prop & done, namespace).fact
    lazy val existsDistribute = remember("(\\forall x_ (p(x_)->q(x_))) -> ((\\exists x_ p(x_))->(\\exists x_ q(x_)))".asFormula,
      implyR(1) & implyR(1) & existsL(-2) & allL(-1) & existsR(1) & prop & done, namespace).fact

    def pushIn(remainder: PosInExpr): DependentPositionTactic = anon ((pp: Position, ss: Sequent) => (ss.sub(pp) match {
      case Some(Imply(left: BinaryCompositeFormula, right: BinaryCompositeFormula)) if left.getClass==right.getClass && left.left==right.left =>
        useAt(propPushLeftIn(left.reapply), PosInExpr(1::Nil))(pp)
      case Some(Imply(left: BinaryCompositeFormula, right: BinaryCompositeFormula)) if left.getClass==right.getClass && left.right ==right.right =>
        useAt(propPushRightIn(left.reapply), PosInExpr(1::Nil))(pp)
      case Some(Imply(Box(a, _), Box(b, _))) if a==b => useAt(Ax.K, PosInExpr(1::Nil))(pp)
      case Some(Imply(Forall(lv, _), Forall(rv, _))) if lv==rv => useAt(Ax.allDist)(pp)
      case Some(Imply(Exists(lv, _), Exists(rv, _))) if lv==rv => useAt(existsDistribute, PosInExpr(1::Nil))(pp)
      case Some(Imply(_, _)) => useAt(implyFact, PosInExpr(1::Nil))(pos)
      case _ => skip
    }) & (if (remainder.pos.isEmpty) skip else pushIn(remainder.child)(pp ++ PosInExpr(remainder.head::Nil))))

    val key = if (polarity > 0) PosInExpr(1::Nil) else if (ga.isEmpty) PosInExpr(0::Nil) else PosInExpr(1::0::Nil)

    if (fact.isProved && ga.isEmpty) useAt(fact, key)(pos)
    else if (fact.isProved && ga.nonEmpty) useAt(fact, key)(pos) & (
      if (polarity < 0) Idioms.<(skip, cohideOnlyR('Rlast) & master() & done | master())
      else cutAt(ga.reduce(And))(pos) & Idioms.<(
        //@todo ensureAt only closes branch when original conjecture is true
        ensureAt(pos) & OnAll(cohideOnlyR(pos) & master() & done | master() & done),
        pushIn(pos.inExpr)(pos.top)
      )
      )
    else throw new TacticInapplicableFailure(s"Invalid transformation: cannot transform ${sequent.sub(pos)} to $to")
  }

  /** Transforms the term at position `pos` into the term `to`. */
  private def transformTerm(to: Term, sequent: Sequent, pos: Position) = {
    val src = sequent.sub(pos) match {
      case Some(src: Term) => src
      case Some(e) => throw new TacticInapplicableFailure("transformTerm only applicable to terms, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + sequent.prettyString)
    }
    useAt(proveBy(Equal(src, to), QE & done), PosInExpr(0::Nil))(pos)
  }

  /** Ensures that the formula at position `pos` is available at that position from the assumptions. */
  private def ensureAt: DependentPositionTactic = anon ((pos: Position, seq: Sequent) => {
    lazy val ensuredFormula = seq.sub(pos) match {
      case Some(fml: Formula) => fml
      case Some(e) => throw new TacticInapplicableFailure("ensureAt only applicable to formulas, but got " + e.prettyString)
      case None => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    }
    lazy val skipAt = anon ((_: Position, _: Sequent) => skip)

    lazy val step = seq(pos.top) match {
      case Box(ODESystem(_, _), _) => diffInvariant(ensuredFormula)(pos.top) & dW(pos.top)
      case Box(Loop(_), _) => loop(ensuredFormula)(pos.top) & Idioms.<(master(), skip, master())
      case Box(Test(_), _) => testb(pos.top) & implyR(pos.top)
      case Box(_, _) => TactixLibrary.step(pos.top)
      case Forall(v, _) if pos.isAnte => allL(v.head)(pos.top)
      case Forall(_, _) if pos.isSucc => allR(pos.top)
      case Exists(v, _) if pos.isSucc => existsR(v.head)(pos.top)
      case Exists(_, _ ) if pos.isAnte => existsL(pos.top)
      //@todo resulting branches may not be provable when starting from wrong question, e.g., a>0&b>0 -> x=2 & a/b>0, even if locally a>0&b>0 -> (a/b>0 <-> a>0*b)
      case e if pos.isAnte => TacticIndex.default.tacticsFor(e)._1.headOption.getOrElse(skipAt)(pos.top)
      case e if pos.isSucc => TacticIndex.default.tacticsFor(e)._2.headOption.getOrElse(skipAt)(pos.top)
    }
    val recurse = if (pos.isTopLevel) skip else ensureAt(pos.top.getPos, pos.inExpr.child)
    if (seq.isFOL) QE else step & onAll(recurse)
  })

  /* Hides all predicates (QE cannot handle predicate symbols) */
  private def hidePredicates: DependentTactic = anon ((sequent: Sequent) =>
    (  sequent.ante.zipWithIndex.filter({ case (f, _) => !f.isPredicateFreeFOL}).reverse.map({ case (fml, i) => hideL(AntePos(i), fml) })
    ++ sequent.succ.zipWithIndex.filter({ case (f, _) => !f.isPredicateFreeFOL}).reverse.map({ case (fml, i) => hideR(SuccPos(i), fml) })
      ).reduceOption[BelleExpr](_ & _).getOrElse(skip)
  )

  /* Hides all predicates (QE cannot handle predicate symbols) */
  private def hideQuantifiedFuncArgsFmls: DependentTactic = anon ((sequent: Sequent) =>
    (  sequent.ante.zipWithIndex.filter({ case (f, _) => !f.isFuncFreeArgsFOL}).reverse.map({ case (fml, i) => hideL(AntePos(i), fml) })
    ++ sequent.succ.zipWithIndex.filter({ case (f, _) => !f.isFuncFreeArgsFOL}).reverse.map({ case (fml, i) => hideR(SuccPos(i), fml) })
      ).reduceOption[BelleExpr](_ & _).getOrElse(skip)
  )

  /** Hides all non-FOL formulas from the sequent. */
  def hideNonFOL: DependentTactic = anon ((sequent: Sequent) =>
    (  sequent.ante.zipWithIndex.filter({ case (fml, _) => !fml.isFOL }).reverse.map({ case (fml, i) => hideL(AntePos(i), fml) })
    ++ sequent.succ.zipWithIndex.filter({ case (fml, _) => !fml.isFOL }).reverse.map({ case (fml, i) => hideR(SuccPos(i), fml) })
      ).reduceOption[BelleExpr](_ & _).getOrElse(skip)
  )
}
