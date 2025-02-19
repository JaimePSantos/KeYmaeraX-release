/**
 * Copyright (c) Carnegie Mellon University. CONFIDENTIAL
 * See LICENSE.txt for the conditions of this license.
 */
package edu.cmu.cs.ls.keymaerax.btactics

import edu.cmu.cs.ls.keymaerax.bellerophon._
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.infrastruct.Augmentors._
import edu.cmu.cs.ls.keymaerax.infrastruct.ExpressionTraversal.ExpressionTraversalFunction
import edu.cmu.cs.ls.keymaerax.infrastruct._
import edu.cmu.cs.ls.keymaerax.btactics.macros.Tactic
import edu.cmu.cs.ls.keymaerax.btactics.TacticFactory._

import scala.collection.immutable._
import scala.collection.mutable.ListBuffer

/**
  * Hilbert Calculus for differential dynamic logic.
  * @author Andre Platzer
  * @author Stefan Mitsch
  * @see [[HilbertCalculus]]
  */
object HilbertCalculus extends HilbertCalculus

/**
  * Hilbert Calculus for differential dynamic logic.
  *
  * Provides the axioms and axiomatic proof rules from Figure 2 and Figure 3 in:
  * Andre Platzer. [[https://doi.org/10.1007/s10817-016-9385-1 A complete uniform substitution calculus for differential dynamic logic]]. Journal of Automated Reasoning, 59(2), pp. 219-266, 2017.
 *
  * @author Andre Platzer
  * @author Stefan Mitsch
  * @see Andre Platzer. [[https://doi.org/10.1007/s10817-016-9385-1 A complete uniform substitution calculus for differential dynamic logic]]. Journal of Automated Reasoning, 59(2), pp. 219-266, 2017.
  * @see Andre Platzer. [[https://doi.org/10.1007/978-3-319-63588-0 Logical Foundations of Cyber-Physical Systems]]. Springer, 2018.
  * @see Andre Platzer. [[https://doi.org/10.1007/978-3-319-21401-6_32 A uniform substitution calculus for differential dynamic logic]].  In Amy P. Felty and Aart Middeldorp, editors, International Conference on Automated Deduction, CADE'15, Berlin, Germany, Proceedings, LNCS. Springer, 2015. [[http://arxiv.org/pdf/1503.01981.pdf A uniform substitution calculus for differential dynamic logic.  arXiv 1503.01981]]
  * @see Andre Platzer. [[https://doi.org/10.1145/2817824 Differential game logic]]. ACM Trans. Comput. Log. 17(1), 2015. [[http://arxiv.org/pdf/1408.1980 arXiv 1408.1980]]
  * @see Andre Platzer. [[https://doi.org/10.1109/LICS.2012.13 Logics of dynamical systems]]. ACM/IEEE Symposium on Logic in Computer Science, LICS 2012, June 25–28, 2012, Dubrovnik, Croatia, pages 13-24. IEEE 2012
  * @see Andre Platzer. [[https://doi.org/10.1109/LICS.2012.64 The complete proof theory of hybrid systems]]. ACM/IEEE Symposium on Logic in Computer Science, LICS 2012, June 25–28, 2012, Dubrovnik, Croatia, pages 541-550. IEEE 2012
  * @see [[HilbertCalculus.stepAt()]]
  * @see [[HilbertCalculus.derive()]]
  * @see [[edu.cmu.cs.ls.keymaerax.core.AxiomBase]]
  * @see [[edu.cmu.cs.ls.keymaerax.btactics.Ax]]
  * @Tactic completed
  */
trait HilbertCalculus extends UnifyUSCalculus {
  import TacticFactory._

  /** True when insisting on internal useAt technology, false when more elaborate external tactic calls are used on demand. */
  private[btactics] val INTERNAL = false

  /**
    * Make the canonical simplifying proof step at the indicated position
    * except when a decision needs to be made (e.g. invariants for loops or for differential equations).
    * Using the canonical [[AxIndex]].
    * @author Andre Platzer
    * @note Efficient source-level indexing implementation.
    * @see [[AxIndex]]
    */
  @Tactic()
  val stepAt: DependentPositionTactic = UnifyUSCalculus.stepAt(AxIndex.axiomFor)
  //= UnifyUSCalculus.stepAt(AxIndex.axiomFor)
  //= anon {(pos:Position) => UnifyUSCalculus.stepAt(AxIndex.axiomFor)(pos)}


  //
  // axiomatic rules
  //

  /** G: Gödel generalization rule reduces a proof of `&Gamma; |- [a]p(x), &Delta;` to proving the postcondition `|- p(x)` in isolation.
    * {{{
    *     |- p(||)
    *   --------------- G
    *   G |- [a]p(||), D
    * }}}
    * This rule is a special case of rule [[monb]] with p(x)=True by [[boxTrue]].
    * @note Unsound for hybrid games
    * @see [[monb]] with p(x)=True
    * @see [[boxTrue]]
    */
  @Tactic(premises = "|- P", conclusion = "Γ |- [a]P, Δ")
  val G : DependentPositionTactic = anon {(pos:Position) => SequentCalculus.cohideR(pos) & DLBySubst.G}

  /** allG: all generalization rule reduces a proof of `\forall x p(x) |- \forall x q(x)` to proving `p(x) |- q(x)` in isolation.
    * {{{
    *      p(x) |- q(x)
    *   ---------------------------------
    *   \forall x p(x) |- \forall x q(x)
    * }}}
    * @see [[UnifyUSCalculus.CMon()]]
    */
    //@todo flexibilize via cohide2 first
  @Tactic(premises = "P |- Q", conclusion = "∀x P |- ∀x Q")
  lazy val monall             : BelleExpr         = anon {byUS(Ax.monall)}
  /** monb: Monotone `[a]p(x) |- [a]q(x)` reduces to proving `p(x) |- q(x)`.
    * {{{
    *      p(x) |- q(x)
    *   ------------------- M[.]
    *   [a]p(x) |- [a]q(x)
    * }}}
    * @see [[UnifyUSCalculus.CMon()]]
    */
  //@todo flexibilize via cohide2 first
  @Tactic(premises = "P |- Q", conclusion = "[a]P |- [a]Q")
  lazy val monb               : BelleExpr         = anon {byUS(Ax.monb)}
  /** mond: Monotone `⟨a⟩p(x) |- ⟨a⟩q(x)` reduces to proving `p(x) |- q(x)`.
    * {{{
    *      p(x) |- q(x)
    *   ------------------- M
    *   ⟨a⟩p(x) |- ⟨a⟩q(x)
    * }}}
    * @see [[UnifyUSCalculus.CMon()]]
    */
  //@todo flexibilize via cohide2 first
  @Tactic(premises = "P |- Q", conclusion = "<a>P |- <a>Q")
  lazy val mond               : BelleExpr         = anon {byUS(Ax.mondrule)}

  //
  // axioms
  //

  //
  //box modality
  //

  /** diamond: <.> reduce double-negated box `![a]!p(x)` to a diamond `⟨a⟩p(x)`. */
  lazy val diamond            : DependentPositionTactic = useAt(Ax.diamond)
  @Tactic(("<·>", "<.>"), conclusion = "__&langle;a&rangle;P__ ↔ &not;[a]&not;P")
  lazy val diamondd            : DependentPositionTactic = HilbertCalculus.useAt(Ax.diamond, PosInExpr(1::Nil))
  /** assignb: [:=] simplify assignment `[x:=f;]p(x)` by substitution `p(f)` or equation.
    * Box assignment by substitution assignment [v:=t();]p(v) <-> p(t()) (preferred),
    * or by equality assignment [x:=f();]p(||) <-> \forall x (x=f() -> p(||)) as a fallback.
    * Universal quantifiers are skolemized if applied at top-level in the succedent; they remain unhandled in the
    * antecedent and in non-top-level context.
    * @example {{{
    *    |- 1>0
    *    --------------------assignb(1)
    *    |- [x:=1;]x>0
    * }}}
    * @example {{{
    *           1>0 |-
    *    --------------------assignb(-1)
    *    [x:=1;]x>0 |-
    * }}}
    * @example {{{
    *    x_0=1 |- [{x_0:=x_0+1;}*]x_0>0
    *    ----------------------------------assignb(1)
    *          |- [x:=1;][{x:=x+1;}*]x>0
    * }}}
    * @example {{{
    *    \\forall x_0 (x_0=1 -> [{x_0:=x_0+1;}*]x_0>0) |-
    *    -------------------------------------------------assignb(-1)
    *                           [x:=1;][{x:=x+1;}*]x>0 |-
    * }}}
    * @example {{{
    *    |- [y:=2;]\\forall x_0 (x_0=1 -> x_0=1 -> [{x_0:=x_0+1;}*]x_0>0)
    *    -----------------------------------------------------------------assignb(1, 1::Nil)
    *    |- [y:=2;][x:=1;][{x:=x+1;}*]x>0
    * }}}
    * @see [[DLBySubst.assignEquality]] */
  @Tactic("[:=]", revealInternalSteps = true, conclusion = "__[x:=e]p(x)__↔p(e)")
  lazy val assignb            : DependentPositionTactic = anon { (pos:Position) =>
    if (INTERNAL) useAt(Ax.assignbAxiom)(pos) |! useAt(Ax.selfassignb)(pos) /*|! useAt(DerivedAxioms.assignbup)(pos)*/
    else useAt(Ax.assignbAxiom)(pos) |! useAt(Ax.selfassignb)(pos) |! DLBySubst.assignEquality(pos)
  }

  /** randomb: [:*] simplify nondeterministic assignment `[x:=*;]p(x)` to a universal quantifier `\forall x p(x)` */
  lazy val randomb            : DependentPositionTactic = useAt(Ax.randomb)
  /** testb: [?] simplifies test `[?q;]p` to an implication `q->p` */
  lazy val testb              : DependentPositionTactic = useAt(Ax.testb)
  /** diffSolve: solve a differential equation `[x'=f]p(x)` to `\forall t>=0 [x:=solution(t)]p(x)` */
  //def diffSolve               : DependentPositionTactic = ???
  /** choiceb: [++] handles both cases of a nondeterministic choice `[a++b]p(x)` separately `[a]p(x) & [b]p(x)` */
  lazy val choiceb            : DependentPositionTactic = useAt(Ax.choiceb)
  /** composeb: [;] handle both parts of a sequential composition `[a;b]p(x)` one at a time `[a][b]p(x)` */
  lazy val composeb           : DependentPositionTactic = useAt(Ax.composeb)

  /** iterateb: [*] prove a property of a loop `[{a}*]p(x)` by unrolling it once `p(x) & [a][{a}*]p(x)` */
  lazy val iterateb           : DependentPositionTactic = useAt(Ax.iterateb)
  /** dualb: [^d^] handle dual game `[{a}^d^]p(x)` by `![a]!p(x)` */
  lazy val dualb              : DependentPositionTactic = useAt(Ax.dualb)

  //
  // diamond modality
  //

  /** box: [.] to reduce double-negated diamond `!⟨a⟩!p(x)` to a box `[a]p(x)`. */
  lazy val box  : DependentPositionTactic = useAt(Ax.box)
  @Tactic(("[·]", "[.]"), conclusion = "__[a]P__ ↔ &not;&langle;a&rangle;&not;P")
  lazy val boxd : DependentPositionTactic = HilbertCalculus.useAt(Ax.box, PosInExpr(1::Nil))

  /** assignd: <:=> simplify assignment `<x:=f;>p(x)` by substitution `p(f)` or equation */
  @Tactic("<:=>", revealInternalSteps = true, conclusion = "__&langle;x:=e&rangle;p(x)__↔p(e)")
  lazy val assignd            : DependentPositionTactic = anon { (pos:Position) =>
    useAt(Ax.assigndAxiom)(pos) |! useAt(Ax.selfassignd)(pos) |! DLBySubst.assigndEquality(pos)
  }

  /** randomd: <:*> simplify nondeterministic assignment `<x:=*;>p(x)` to an existential quantifier `\exists x p(x)` */
  lazy val randomd            : DependentPositionTactic = useAt(Ax.randomd)
  /** testd: <?> simplifies test `<?q;>p` to a conjunction `q&p` */
  lazy val testd              : DependentPositionTactic = useAt(Ax.testd)
  /** diffSolve: solve a differential equation `<x'=f>p(x)` to `\exists t>=0 <x:=solution(t)>p(x)` */
  //def diffSolved              : DependentPositionTactic = ???
  /** choiced: <++> handles both cases of a nondeterministic choice `⟨a++b⟩p(x)` separately `⟨a⟩p(x) | ⟨b⟩p(x)` */
  lazy val choiced            : DependentPositionTactic = useAt(Ax.choiced)
  /** composed: <;> handle both parts of a sequential composition `⟨a;b⟩p(x)` one at a time `⟨a⟩⟨b⟩p(x)` */
  lazy val composed           : DependentPositionTactic = useAt(Ax.composed)
  /** iterated: <*> prove a property of a loop `⟨{a}*⟩p(x)` by unrolling it once `p(x) | ⟨a⟩⟨{a}*⟩p(x)` */
  lazy val iterated           : DependentPositionTactic = useAt(Ax.iterated)
  /** duald: `<^d^>` handle dual game `⟨{a}^d^⟩p(x)` by `!⟨a⟩!p(x)` */
  lazy val duald              : DependentPositionTactic = useAt(Ax.duald)

  @Tactic(("⟨:=⟩D", "<:=>D"), conclusion = "__&langle;x:=f();&rangle;P__ ↔ [x:=f();]P")
  lazy val assigndDual: DependentPositionTactic = HilbertCalculus.useAt(Ax.assignDual2)
  @Tactic(("[:=]D", "[:=]D"), conclusion = "&langle;x:=f();&rangle;P ↔ __[x:=f();]P__")
  lazy val assignbDual: DependentPositionTactic = HilbertCalculus.useAt(Ax.assignDual2, PosInExpr(1::Nil))

//  /** I: prove a property of a loop by induction with the given loop invariant (hybrid systems) */
//  def I(invariant : Formula)  : PositionTactic = TacticLibrary.inductionT(Some(invariant))
//  def loop(invariant: Formula) = I(invariant)
  /** K: modal modus ponens (hybrid systems)
    * @note Use with care since limited to hybrid systems. Use [[monb]] instead.
    * @see [[monb]]
    * @see [[mond]]
    */
  lazy val K                  : DependentPositionTactic = useAt(Ax.K)
  /** V: vacuous box `[a]p()` will be discarded and replaced by `p()` provided program `a` does not change values of postcondition `p()`.
    * @note Unsound for hybrid games
    */
  lazy val V                  : DependentPositionTactic = useAt(Ax.V)
  /** VK: vacuous box `[a]p()` will be discarded and replaced by `p()` provided program `a` does not change values of postcondition `p()`
    * and provided `[a]true` proves, e.g., since `a` is a hybrid system.
    */
  lazy val VK                 : DependentPositionTactic = useAt(Ax.VK)

  //
  // differential equations
  //

  /** DW: Differential Weakening to use evolution domain constraint `[{x'=f(x)&q(x)}]p(x)` reduces to `[{x'=f(x)&q(x)}](q(x)->p(x))` */
  lazy val DW                 : DependentPositionTactic = useAt(Ax.DW)
  /** DWd: Diamond Differential Weakening to use evolution domain constraint `<{x'=f(x)&q(x)}>p(x)` reduces to `<{x'=f(x)&q(x)}>(q(x)&p(x))` */
  lazy val DWd                 : DependentPositionTactic = useAt(Ax.DWd)
  /** DC: Differential Cut a new invariant for a differential equation `[{x'=f(x)&q(x)}]p(x)` reduces to `[{x'=f(x)&q(x)&C(x)}]p(x)` with `[{x'=f(x)&q(x)}]C(x)`. */
  @Tactic(conclusion = "(__[x'=f(x)&Q]P__↔[x'=f(x)&Q∧R]P)←[x'=f(x)&Q]R", inputs = "R:formula", revealInternalSteps = true)
  def DC(invariant: Formula)  : DependentPositionWithAppliedInputTactic = inputanon {(pos: Position) =>
    useAt(Ax.DC,
      (us:Option[Subst])=>us.getOrElse(throw new UnsupportedTacticFeature("Unexpected missing substitution in DC"))++RenUSubst(Seq((UnitPredicational("r",AnyArg), invariant)))
    )(pos)
  }
  /** DCd: Diamond Differential Cut a new invariant for a differential equation `<{x'=f(x)&q(x)}>p(x)` reduces to `<{x'=f(x)&q(x)&C(x)}>p(x)` with `[{x'=f(x)&q(x)}]C(x)`. */
  @Tactic(conclusion = "(__<x'=f(x)&Q>P__↔<x'=f(x)&Q∧R>P)←[x'=f(x)&Q]R", inputs = "R:formula", revealInternalSteps = true)
  def DCd(invariant: Formula)  : DependentPositionWithAppliedInputTactic = inputanon {(pos: Position) =>
    useAt(Ax.DCd,
      (us:Option[Subst])=>us.getOrElse(throw new UnsupportedTacticFeature("Unexpected missing substitution in DCd"))++RenUSubst(Seq((UnitPredicational("r",AnyArg), invariant)))
    )(pos)
  }
  /** DE: Differential Effect exposes the effect of a differential equation `[x'=f(x)]p(x,x')` on its differential symbols
    * as `[x'=f(x)][x':=f(x)]p(x,x')` with its differential assignment `x':=f(x)`.
    * {{{
    *   G |- [{x'=f(||)&H(||)}][x':=f(||);]p(||), D
    *   -------------------------------------------
    *   G |- [{x'=f(||)&H(||)}]p(||), D
    * }}}
    *
    * @example {{{
    *    |- [{x'=1}][x':=1;]x>0
    *    -----------------------DE(1)
    *    |- [{x'=1}]x>0
    * }}}
    * @example {{{
    *    |- [{x'=1, y'=x & x>0}][y':=x;][x':=1;]x>0
    *    -------------------------------------------DE(1)
    *    |- [{x'=1, y'=x & x>0}]x>0
    * }}}
    */
  lazy val DE                 : DependentPositionTactic = DifferentialTactics.DE
  /** DI: Differential Invariants are used for proving a formula to be an invariant of a differential equation.
    * `[x'=f(x)&q(x)]p(x)` reduces to `q(x) -> p(x) & [x'=f(x)]p(x)'`.
    * @see [[DifferentialTactics.diffInd()]] */
  lazy val DI                 : DependentPositionTactic = useAt(Ax.DI)

  //@todo replace with a DG(DifferentialProgram) tactic instead to use said axiom.

  /** DGC: Differential ghost add auxiliary differential equation with extra constant g */
  private[btactics] def DGC(y:Variable, b:Term) =
    useAt(Ax.DGC, PosInExpr(0::Nil),
      (us:Option[Subst])=>{
        val singular = FormulaTools.singularities(b)
        insist(singular.isEmpty, "Possible singularities during DG(" + DifferentialSymbol(y) + "=" + b + ") will be rejected: " + singular.mkString(","))
        us.getOrElse(throw new UnsupportedTacticFeature("Unexpected missing substitution in DG"))++RenUSubst(Seq(
          (Variable("y_",None,Real), y),
          (UnitFunctional("b", Except(Variable("y_", None, Real)::Nil), Real), b)
        ))
      }
    )

  //@todo unclear
  private[btactics] def DGCa(y:Variable, b:Term) =
    useAt(Ax.DGCa, PosInExpr(0::Nil),
      (us:Option[Subst])=>{
        val singular = FormulaTools.singularities(b)
        insist(singular.isEmpty, "Possible singularities during DG(" + DifferentialSymbol(y) + "=" + b + ") will be rejected: " + singular.mkString(","))
        us.getOrElse(throw new UnsupportedTacticFeature("Unexpected missing substitution in DG"))++RenUSubst(Seq(
          (Variable("y_",None,Real), y),
          (UnitFunctional("b", Except(Variable("y_", None, Real)::Nil), Real), b)
        ))
      }
    )

  /** DGC: Differential ghost add auxiliary differential equation with extra constant g */
  private[btactics] def DGCd(y:Variable, b:Term) =
  useAt(Ax.DGCd, PosInExpr(0::Nil),
    (us:Option[Subst])=>{
      val singular = FormulaTools.singularities(b)
      insist(singular.isEmpty, "Possible singularities during DG(" + DifferentialSymbol(y) + "=" + b + ") will be rejected: " + singular.mkString(","))
      us.getOrElse(throw new UnsupportedTacticFeature("Unexpected missing substitution in DGd"))++RenUSubst(Seq(
        (Variable("y_",None,Real), y),
        (UnitFunctional("b", Except(Variable("y_", None, Real)::Nil), Real), b)
      ))
    }
  )
  private[btactics] def DGCde(y:Variable, b:Term) =
    useAt(Ax.DGCde, PosInExpr(0::Nil),
      (us:Option[Subst])=>{
        val singular = FormulaTools.singularities(b)
        insist(singular.isEmpty, "Possible singularities during DG(" + DifferentialSymbol(y) + "=" + b + ") will be rejected: " + singular.mkString(","))
        us.getOrElse(throw new UnsupportedTacticFeature("Unexpected missing substitution in DGde"))++RenUSubst(Seq(
          (Variable("y_",None,Real), y),
          (UnitFunctional("b", Except(Variable("y_", None, Real)::Nil), Real), b)
        ))
      }
    )

  //  /** DA: Differential Ghost add auxiliary differential equations with extra variables y'=a*y+b and replacement formula */
//  def DA(y:Variable, a:Term, b:Term, r:Formula) : PositionTactic = ODETactics.diffAuxiliariesRule(y,a,b,r)
  /** DS: Differential Solution solves a simple differential equation `[x'=c&q(x)]p(x)` by reduction to
    * `\forall t>=0 ((\forall 0<=s<=t  q(x+c()*s) -> [x:=x+c()*t;]p(x))` */
  lazy val DS                 : DependentPositionTactic = useAt(Ax.DS)

  /** Dassignb: [':=] Substitute a differential assignment `[x':=f]p(x')` to `p(f)` */
  //@note potential incompleteness here should not ever matter
  lazy val Dassignb           : DependentPositionTactic =  useAt(Ax.Dassignb)

  /*******************************************************************
    * Derive by proof
    *******************************************************************/

  /** Derive the differential expression at the indicated position (Hilbert computation deriving the answer by proof).
    * @example When applied at 1::Nil, turns [{x'=22}](2*x+x*y>=5)' into [{x'=22}]2*x'+x'*y+x*y'>=0
    * @see [[UnifyUSCalculus.chase]]
    */
  @Tactic("()'", revealInternalSteps = false /* uninformative as useFor proof */)
  lazy val derive: DependentPositionTactic = anon {(pos:Position, seq: Sequent) =>
    val chaseNegations = seq.sub(pos) match {
      case Some(post: DifferentialFormula) =>
        val notPositions = ListBuffer.empty[PosInExpr]
        ExpressionTraversal.traverse(new ExpressionTraversalFunction() {
          override def preF(p: PosInExpr, e: Formula): Either[Option[ExpressionTraversal.StopTraversal], Formula] = e match {
            case Not(_) if !notPositions.exists(_.isPrefixOf(p)) => notPositions.append(p); Left(None)
            case _ => Left(None)
          }
        }, post)
        notPositions.map(p => chase('Rlast, pos.inExpr ++ p)).
          reduceRightOption[BelleExpr](_ & _).getOrElse(skip)
      case _ => skip
    }

    chaseNegations & chase(pos) & anon { (seq: Sequent) => {
      seq.sub(pos) match {
        case Some(e: Expression) =>
          val dvarPositions = ListBuffer.empty[PosInExpr]
          ExpressionTraversal.traverseExpr(new ExpressionTraversalFunction() {
            override def preT(p: PosInExpr, t: Term): Either[Option[ExpressionTraversal.StopTraversal], Term] = t match {
              case Differential(_: Variable) => dvarPositions.append(p); Left(None)
              case _ => Left(None)
            }
          }, e)
          dvarPositions.map(p => DifferentialTactics.Dvariable(pos ++ p)).
            reduceRightOption[BelleExpr](_ & _).getOrElse(skip)
        case _ => skip
      }
    }}
  }

  /**
    * Derive: provides individual differential axioms bundled as [[HilbertCalculus.derive]].
    *
    * There is rarely a reason to use these separate axioms, since [[HilbertCalculus.derive]] already
    * uses the appropriate differential axiom as needed.
    * @see Figure 3 in Andre Platzer. [[https://doi.org/10.1007/s10817-016-9385-1 A complete uniform substitution calculus for differential dynamic logic]]. Journal of Automated Reasoning, 59(2), pp. 219-266, 2017.
    * @see [[HilbertCalculus.derive]]
    */
  object Derive {
    /** Dplus: +' derives a sum `(f(x)+g(x))' = (f(x))' + (g(x))'` */
    lazy val Dplus              : DependentPositionTactic = useAt(Ax.Dplus)
    /** neg: -' derives unary negation `(-f(x))' = -(f(x)')` */
    lazy val Dneg               : DependentPositionTactic = useAt(Ax.Dneg)
    /** Dminus: -' derives a difference `(f(x)-g(x))' = (f(x))' - (g(x))'` */
    lazy val Dminus             : DependentPositionTactic = useAt(Ax.Dminus)
    /** Dtimes: *' derives a product `(f(x)*g(x))' = f(x)'*g(x) + f(x)*g(x)'` */
    lazy val Dtimes             : DependentPositionTactic = useAt(Ax.Dtimes)
    /** Dquotient: /' derives a quotient `(f(x)/g(x))' = (f(x)'*g(x) - f(x)*g(x)') / (g(x)^2)` */
    lazy val Dquotient          : DependentPositionTactic = useAt(Ax.Dquotient)
    /** Dpower: ^' derives a power */
    lazy val Dpower             : DependentPositionTactic = useAt(Ax.Dpower)
    /** Dcompose: o' derives a function composition by chain rule */
      //@todo not sure if useAt can handle this yet
    lazy val Dcompose           : DependentPositionTactic = useAt(Ax.Dcompose)
    /** Dconst: c()' derives a constant `c()' = 0` */
    lazy val Dconst             : DependentPositionTactic = useAt(Ax.Dconst)
    /** Dvariable: x' derives a variable `(x)' = x'`
      * Syntactically derives a differential of a variable to a differential symbol.
      * {{{
      *   G |- x'=f, D
      *   --------------
      *   G |- (x)'=f, D
      * }}}
      *
      * @example {{{
      *   |- x'=1
      *   ----------Dvariable(1, 0::Nil)
      *   |- (x)'=1
      * }}}
      * @example {{{
      *   |- [z':=1;]z'=1
      *   ------------------Dvariable(1, 1::0::Nil)
      *   |- [z':=1;](z)'=1
      * }}}
      * @incontext
      */
    @Tactic("(x)'", conclusion="(x)' = x", displayLevel = "browse")
    lazy val Dvar: DependentPositionTactic = anon {(pos:Position) => (if (INTERNAL) useAt(Ax.Dvar) else DifferentialTactics.Dvariable)(pos)}

    /** Dand: &' derives a conjunction `(p(x)&q(x))'` to obtain `p(x)' & q(x)'` */
    lazy val Dand               : DependentPositionTactic = useAt(Ax.Dand)
    /** Dor: |' derives a disjunction `(p(x)|q(x))'` to obtain `p(x)' & q(x)'` */
    lazy val Dor                : DependentPositionTactic = useAt(Ax.Dor)
    /** Dimply: ->' derives an implication `(p(x)->q(x))'` to obtain `(!p(x) | q(x))'` */
    lazy val Dimply             : DependentPositionTactic = useAt(Ax.Dimply)
    /** Dequal: =' derives an equation `(f(x)=g(x))'` to obtain `f(x)'=g(x)'` */
    lazy val Dequal             : DependentPositionTactic = useAt(Ax.Dequal)
    /** Dnotequal: !=' derives a disequation `(f(x)!=g(x))'` to obtain `f(x)'=g(x)'` */
    lazy val Dnotequal          : DependentPositionTactic = useAt(Ax.Dnotequal)
    /** Dless: <' derives less-than `(f(x)⟨g(x))'` to obtain `f(x)'<=g(x)'` */
    lazy val Dless              : DependentPositionTactic = useAt(Ax.Dless)
    /** Dlessequal: <=' derives a less-or-equal `(f(x)<=g(x))'` to obtain `f(x)'<=g(x)'` */
    lazy val Dlessequal         : DependentPositionTactic = useAt(Ax.Dlessequal)
    /** Dgreater: >' derives greater-than `(f(x)>g(x))'` to obtain `f(x)'>=g(x)'` */
    lazy val Dgreater           : DependentPositionTactic = useAt(Ax.Dgreater)
    /** Dgreaterequal: >=' derives a greater-or-equal `(f(x)>=g(x))'` to obtain `f(x)'>=g(x)'` */
    lazy val Dgreaterequal      : DependentPositionTactic = useAt(Ax.Dgreaterequal)
    /** Dforall: \forall' derives an all quantifier `(\forall x p(x))'` to obtain `\forall x (p(x)')` */
    lazy val Dforall            : DependentPositionTactic = useAt(Ax.Dforall)
    /** Dexists: \exists' derives an exists quantifier */
    lazy val Dexists            : DependentPositionTactic = useAt(Ax.Dexists)
  }

  //
  // Additional
  //

  /** boxAnd: splits `[a](p&q)` into `[a]p & [a]q` */
  lazy val boxAnd             : DependentPositionTactic = useAt(Ax.boxAnd)
  /** diamondOr: splits `⟨a⟩(p|q)` into `⟨a⟩p | ⟨a⟩q` */
  lazy val diamondOr          : DependentPositionTactic = useAt(Ax.diamondOr)
  /** boxImpliesAnd: splits `[a](p->q&r)` into `[a](p->q) & [a](p->r)` */
  lazy val boxImpliesAnd      : DependentPositionTactic = useAt(Ax.boxImpliesAnd)

  // def ind

  /** boxTrue: proves `[a]true` directly for hybrid systems `a` that are not hybrid games. */
  val boxTrue                : DependentPositionTactic = useAt(Ax.boxTrue)


  /*******************************************************************
    * First-order logic
    *******************************************************************/

  /** allV: vacuous `\forall x p()` will be discarded and replaced by p() provided x does not occur in p(). */
  lazy val allV               : DependentPositionTactic = useAt(Ax.allV)
  /** existsV: vacuous `\exists x p()` will be discarded and replaced by p() provided x does not occur in p(). */
  lazy val existsV            : DependentPositionTactic = useAt(Ax.existsV)
  /** allDist: distribute `\forall x p(x) -> \forall x q(x)` by replacing it with `\forall x (p(x)->q(x))`.
    * @see [[allDistElim]] */
  lazy val allDist            : DependentPositionTactic = useAt(Ax.allDist)
  /** allDistElim: distribute `\forall x P -> \forall x Q` by replacing it with `\forall x (P->Q)`. */
  lazy val allDistElim        : DependentPositionTactic = useAt(Ax.allDistElim)

  /** existsE: show `\exists x P` by showing that it follows from `P`. */
  lazy val existsE            : DependentPositionTactic = useAt(Ax.existse)

}
