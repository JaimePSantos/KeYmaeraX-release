/**
  * Copyright (c) Carnegie Mellon University. CONFIDENTIAL
  * See LICENSE.txt for the conditions of this license.
  */
package edu.cmu.cs.ls.keymaerax.btactics

import edu.cmu.cs.ls.keymaerax.bellerophon._
import edu.cmu.cs.ls.keymaerax.infrastruct.ExpressionTraversal.ExpressionTraversalFunction
import edu.cmu.cs.ls.keymaerax.btactics.TacticIndex.{BranchRecursor, Branches, TacticRecursor, TacticRecursors}
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.infrastruct.Augmentors.SequentAugmentor
import edu.cmu.cs.ls.keymaerax.infrastruct._

import scala.annotation.switch

/**
 * Tactic indexing data structures for canonical proof strategies.
 * @author Stefan Mitsch
 * @see [[edu.cmu.cs.ls.keymaerax.btactics.AxiomInfo]]
 */
object TacticIndex {
  /** A list of things to do on a branch */
  type Branch = List[PositionLocator]

  /** Branch recursors (parent tactics may branch). */
  type Branches = List[Branch]

  /** Where to recurse on one branch. */
  case class BranchRecursor(positions: List[PositionLocator])

  /** Where to recurse on the many branches created by a tactic. */
  case class TacticRecursor(branches: List[BranchRecursor])

  /**
    * Recursors list resulting siblings for subsequent chase (every recursor is itself
    * a list, since tactics may branch). Left=recursor on applying, Right=recursor on skipping (to chase inside)
    */
  type TacticRecursors = (Sequent, Position) => Either[TacticRecursor, TacticRecursor]

  lazy val default: TacticIndex = new DefaultTacticIndex

  val allLStutter: DependentPositionTactic = TactixLibrary.useAt(Ax.allStutter)
  val existsRStutter: DependentPositionTactic = TactixLibrary.useAt(Ax.existsStutter)
}

/**
  * @see [[AxIndex]]
  */
trait TacticIndex {
  /** Recursors pointing to the result positions of `tactic`. */
  def tacticRecursors(tactic: BelleExpr): TacticRecursors
  /** Returns canonical tactics to try at `expr` restricted to the ones in `restrictTo` (tuple of antecedent and succedent tactics) */
  def tacticFor(expr: Expression, restrictTo: List[AtPosition[_ <: BelleExpr]]): (Option[AtPosition[_ <: BelleExpr]], Option[AtPosition[_ <: BelleExpr]])
  /** Returns canonical tactics to try at `expr` (tuple of antecedent and succedent tactics) */
  def tacticsFor(expr: Expression): (List[AtPosition[_ <: BelleExpr]], List[AtPosition[_ <: BelleExpr]])
  /** Indicates whether `tactic` is potentially applicable at `expr`. */
  def isApplicable(expr: Expression, tactic: BelleExpr): Boolean
}

class DefaultTacticIndex extends TacticIndex {

  private val stop: TacticRecursor = TacticRecursor(Nil)
  private def one(pos: List[PositionLocator]): TacticRecursor = TacticRecursor(BranchRecursor(pos) :: Nil)
  private def one(pos: PositionLocator): TacticRecursor = one(pos :: Nil)
  private def two(p1: List[PositionLocator], p2: List[PositionLocator]): TacticRecursor = TacticRecursor(BranchRecursor(p1) :: BranchRecursor(p2) :: Nil)
  private def two(p1: PositionLocator, p2: PositionLocator): TacticRecursor = two(p1 :: Nil, p2 :: Nil)


  /** Return tactic index with list of recursors on other sibling, i.e., for chasing after the tactic is applied. */
  def tacticRecursors(tactic: BelleExpr): TacticRecursors = (tactic: @switch) match {
    //@note IMPORTANT: add only tactics with tactic annotations here! otherwise, will match with whatever based on the name ANON
    //@note expected formulas are used to fall back to search
    case TactixLibrary.notL => (s: Sequent, p: Position) =>
      if (p.isTopLevel) Left(one(Fixed(SuccPosition.base0(s.succ.length), child(s(p.checkTop)))))
      else Right(one(child(s, p)))
    case TactixLibrary.andL => (s: Sequent, p: Position) =>
      if (p.isTopLevel) Left(
        one(Fixed(AntePosition.base0(s.ante.length), right(s(p.checkTop))) ::
            Fixed(AntePosition.base0(s.ante.length-1), left(s(p.checkTop))) :: Nil))
      else Right(one(left(s, p) :: right(s, p) :: Nil))
    case TactixLibrary.orL => (s: Sequent, p: Position) =>
      if (p.isTopLevel) Left(two(Fixed(p, left(s(p.checkTop))), Fixed(p, right(s(p.checkTop)))))
      else Right(one(left(s, p) :: right(s, p) :: Nil))
    case TactixLibrary.implyL => (s: Sequent, p: Position) =>
      if (p.isTopLevel) Left(two(Fixed(SuccPosition.base0(s.succ.length), left(s(p.checkTop))), Fixed(p, right(s(p.checkTop)))))
      else Right(one(left(s, p) :: right(s, p) :: Nil))
    case TactixLibrary.equivL => (s: Sequent, p: Position) =>
      if (p.isTopLevel) Left(two(
        Fixed(p, Some(And(left(s(p.checkTop)).get, right(s(p.checkTop)).get))),
        Fixed(p, Some(And(Not(left(s(p.checkTop)).get), Not(right(s(p.checkTop)).get))))))
      else Right(one(left(s, p) :: right(s, p) :: Nil))
    case TactixLibrary.notR => (s: Sequent, p: Position) =>
      if (p.isTopLevel) Left(one(Fixed(AntePosition.base0(s.ante.length), child(s(p.checkTop)))))
      else Right(one(child(s, p)))
    case TactixLibrary.implyR => (s: Sequent, p: Position) =>
      if (p.isTopLevel) Left(
        one(Fixed(AntePosition.base0(s.ante.length), left(s(p.checkTop))) ::
            Fixed(SuccPosition.base0(s.succ.length-1), right(s(p.checkTop))) :: Nil))
      else Right(one(left(s, p) :: right(s, p) :: Nil))
    case TactixLibrary.orR => (s: Sequent, p: Position) =>
      if (p.isTopLevel) Left(
        one(Fixed(SuccPosition.base0(s.succ.length), right(s(p.checkTop))) ::
            Fixed(SuccPosition.base0(s.succ.length-1), left(s(p.checkTop))) :: Nil))
      else Right(one(left(s, p) :: right(s, p) :: Nil))
    case TactixLibrary.andR => (s: Sequent, p: Position) =>
      if (p.isTopLevel) Left(two(Fixed(p, left(s(p.checkTop))), Fixed(p, right(s(p.checkTop)))))
      else Right(one(left(s, p) :: right(s, p) :: Nil))
    case TactixLibrary.equivR => (s: Sequent, p: Position) =>
      if (p.isTopLevel) Left(two(
        Fixed(AntePosition.base0(s.ante.length), left(s(p.checkTop))) :: Fixed(SuccPosition.base0(s.succ.length-1), right(s(p.checkTop))) :: Nil,
        Fixed(AntePosition.base0(s.ante.length), right(s(p.checkTop))) :: Fixed(SuccPosition.base0(s.succ.length-1), left(s(p.checkTop))) :: Nil))
      else Right(one(left(s, p) :: right(s, p) :: Nil))
    case TactixLibrary.step => (_: Sequent, p: Position) => Left(one(Fixed(p)))
    case TactixLibrary.allR => (s: Sequent, p: Position) =>
      if (p.isTopLevel) Left(one(Fixed(p, child(s(p.checkTop)))))
      else Right(one(child(s, p)))
    case TacticIndex.allLStutter => (s: Sequent, p: Position) => {
      val (childPos, c) = (PosInExpr(0::Nil), s.sub(p) match {
        case Some(f: Forall) => child(f)
        case _ => throw new IllFormedTacticApplicationException("Position " + p.prettyString + " does not point to universal quantifier in " + s.prettyString)
      })
      Left(one(new Fixed(p ++ childPos, c)))
    }
    case TactixLibrary.existsL => (s: Sequent, p: Position) =>
      if (p.isTopLevel) Left(one(new Fixed(p, child(s(p.checkTop)))))
      else Right(one(child(s, p)))
    case TacticIndex.existsRStutter => (s: Sequent, p: Position) => {
      val (childPos, c) = (PosInExpr(0::Nil), s.sub(p) match {
        case Some(f: Exists) => child(f)
        case _ => throw new IllFormedTacticApplicationException("Position " + p.prettyString + " does not point to existential quantifier in " + s.prettyString)
      })
      Left(one(new Fixed(p ++ childPos, c)))
    }
    case TactixLibrary.ODE => (_: Sequent, p: Position) => Left(one(new Fixed(p)))
    case TactixLibrary.solve => (_: Sequent, p: Position) => Left(one(new Fixed(p)))
    case PropositionalTactics.autoMP => (s: Sequent, p: Position) =>
      if (p.isTopLevel) Left(one(new Fixed(p.checkAnte.checkTop)))
      else Right(one(left(s, p) :: right(s, p) :: Nil))
    // default position: stop searching
    case _ => (_: Sequent, _: Position) => Left(stop)
  }

  private def child[T <: Expression](e: Option[T]): Option[Formula] = e.map({
    case f: UnaryCompositeFormula => f.child
    case Box(_, p) => p
    case Diamond(_, p) => p
    case Forall(_, p) => p
    case Exists(_, p) => p
  })
  private def child(fml: Formula): Option[Formula] = child(Some(fml))
  private def child(s: Sequent, p: Position): PositionLocator = Fixed(p ++ PosInExpr(0::Nil), child(s.sub(p)))
  private def left(e: Option[Expression]): Option[Formula] = e.map({ case f: BinaryCompositeFormula => f.left })
  private def left(fml: Formula): Option[Formula] = left(Some(fml))
  private def left(s: Sequent, p: Position): PositionLocator = Fixed(p ++ PosInExpr(0::Nil), left(s.sub(p)))
  private def right(e: Option[Expression]): Option[Formula] = e.map({ case f: BinaryCompositeFormula => f.right })
  private def right(fml: Formula): Option[Formula] = right(Some(fml))
  private def right(s: Sequent, p: Position): PositionLocator = Fixed(p ++ PosInExpr(1::Nil), right(s.sub(p)))

  // lookup canonical axioms for an expression (index)

  /** Give the first canonical (derived) axiom name or tactic name that simplifies the expression `expr`. */
  def tacticFor(expr: Expression, restrictTo: List[AtPosition[_ <: BelleExpr]]): (Option[AtPosition[_ <: BelleExpr]], Option[AtPosition[_ <: BelleExpr]]) = {
    val tactics = tacticsFor(expr)
    (tactics._1.intersect(restrictTo).headOption, tactics._2.intersect(restrictTo).headOption)
  }

  /** Return ordered list of all canonical tactic names that simplifies the expression `expr` (ante, succ). */
  def tacticsFor(expr: Expression): (List[AtPosition[_ <: BelleExpr]], List[AtPosition[_ <: BelleExpr]]) = {
    if (expr.kind == TermKind) expr match {
      case _ => (Nil, Nil)
    } else expr match {
      case Box(a, p) =>
        val bv = StaticSemantics.boundVars(a)
        a match {
          case _: ODESystem if  bv.intersect(StaticSemantics.freeVars(p)).isEmpty => (TactixLibrary.solve::Nil, TactixLibrary.dW::Nil)
          case _: ODESystem if !bv.intersect(StaticSemantics.freeVars(p)).isEmpty => (TactixLibrary.solve::Nil, TactixLibrary.ODE::TactixLibrary.solve::Nil)
          case _ => (TactixLibrary.step::Nil, DLBySubst.safeabstractionb::TactixLibrary.step::Nil)
        }
      case Diamond(a, _) if !a.isInstanceOf[ODESystem] && !a.isInstanceOf[Loop] => (TactixLibrary.step::Nil, TactixLibrary.step::Nil)
      case Diamond(a, _) if a.isInstanceOf[ODESystem] => (TactixLibrary.solve::Nil, TactixLibrary.solve::Nil)
      case Forall(_, _) => (TacticIndex.allLStutter::Nil, TactixLibrary.allR::Nil)
      case Exists(_, _) => (TactixLibrary.existsL::Nil, TacticIndex.existsRStutter::Nil)
      case Not(_) => (TactixLibrary.notL::Nil, TactixLibrary.notR::Nil)
      case And(_, _) => (TactixLibrary.andL::Nil, TactixLibrary.andR::Nil)
      case Or(_, _) => (TactixLibrary.orL::Nil, TactixLibrary.orR::Nil)
      case Imply(_, _) => (PropositionalTactics.autoMP::TactixLibrary.implyL::Nil, TactixLibrary.implyR::Nil)
      case Equiv(_, _) => (TactixLibrary.equivL::Nil, TactixLibrary.equivR::Nil)
      case True => (Nil, ProofRuleTactics.closeTrue::Nil)
      case False => (ProofRuleTactics.closeFalse::Nil, Nil)
      case _ => (Nil, Nil)
    }
  }

  def isApplicable(expr: Expression, tactic: BelleExpr): Boolean = {
    val name = tactic match {
      case AppliedPositionTactic(CoreRightTactic(n), _) => n
      case AppliedPositionTactic(CoreLeftTactic(n), _) => n
      case AppliedPositionTactic(BuiltInRightTactic(n), _) => n
      case AppliedPositionTactic(BuiltInLeftTactic(n), _) => n
      case DependentTactic(n) => n
      case DependentPositionTactic(n) => n
      case _ => ""
    }
    name match {
      // propositional
      case "orL"    | "orR"    => expr.isInstanceOf[Or]
      case "andL"   | "andR"   => expr.isInstanceOf[And]
      case "implyL" | "implyR" => expr.isInstanceOf[Imply]
      case "equivL" | "equivR" => expr.isInstanceOf[Equiv]
      case "notL"   | "notR"   => expr.isInstanceOf[Not]
      case "CloseTrue"         => expr == True
      case "CloseFalse"        => expr == False

      // box
      case "assignb"  => expr match { case Box(_: Assign, _) => true case _ => false }
      case "randomb"  => expr match { case Box(_: AssignAny, _) => true case _ => false }
      case "choiceb"  => expr match { case Box(_: Choice, _) => true case _ => false }
      case "testb"    => expr match { case Box(_: Test, _) => true case _ => false }
      case "composeb" => expr match { case Box(_: Compose, _) => true case _ => false }
      case "iterateb" | "loop" | "loopauto" =>
        expr match { case Box(_: Loop, _) => true case _ => false }
      case "GV"       => expr.isInstanceOf[Box]

      // diamond
      case "assignd"  => expr match { case Diamond(_: Assign, _) => true case _ => false }
      case "randomd"  => expr match { case Diamond(_: AssignAny, _) => true case _ => false }
      case "choiced"  => expr match { case Diamond(_: Choice, _) => true case _ => false }
      case "testd"    => expr match { case Diamond(_: Test, _) => true case _ => false }
      case "composed" => expr match { case Diamond(_: Compose, _) => true case _ => false }

      // FOL
      case "allL" | "allR" | "all stutter" => expr.isInstanceOf[Forall]
      case "existsL" | "existsR" | "exists stutter" => expr.isInstanceOf[Exists]
      case "allL2R" | "allR2L" | "equalCommute" => expr.isInstanceOf[Equal]
      case "abs" => expr match {
        case Equal(FuncOf(Function("abs", None, Real, Real, true), _), _) => true
        case FuncOf(Function("abs", None, Real, Real, true), _) => true
        case _ => false
      }
      case "minmax" => expr match {
        case Equal(FuncOf(Function("min" | "max", None, Tuple(Real, Real), Real, true), _), _) => true
        case FuncOf(Function("min" | "max", None, Tuple(Real, Real), Real, true), _) => true
        case _ => false
      }

      // box/diamond
      case "solve" | "solveEnd" | "ODE" | "dC" | "dI" | "dW" | "diffInvariant" =>
        expr match { case Box(_: ODESystem, _) => true case Diamond(_: ODESystem, _) => true case _ => false }

      // fallback to trial-and-error in all other cases
      case _ => true
    }
  }

}
