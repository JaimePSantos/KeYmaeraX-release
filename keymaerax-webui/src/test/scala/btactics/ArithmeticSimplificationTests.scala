/*
 * Copyright (c) Carnegie Mellon University.
 * See LICENSE.txt for the conditions of this license.
 */

package btactics

import edu.cmu.cs.ls.keymaerax.btactics.ArithmeticSimplification._
import edu.cmu.cs.ls.keymaerax.bellerophon.{OnAll, SaturateTactic}
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import edu.cmu.cs.ls.keymaerax.btactics.TactixLibrary._
import edu.cmu.cs.ls.keymaerax.btactics.arithmetic.signanalysis.{Bound, Sign, SignAnalysis}
import edu.cmu.cs.ls.keymaerax.btactics.arithmetic.speculative.ArithmeticSpeculativeSimplification
import edu.cmu.cs.ls.keymaerax.btactics.{ArithmeticSimplification, DebuggingTactics, TacticTestBase, TactixLibrary}
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.tags.SlowTest
import testHelper.KeYmaeraXTestTags

import scala.collection.immutable._
import scala.language.postfixOps

/**
  * @author Nathan Fulton
  */
@SlowTest
//@todo @IgnoreInBuildTest
class ArithmeticSimplificationTests extends TacticTestBase {
  "smartHide" should "simplify x=1,y=1 ==> x=1 to x=1 ==> x=1" in withMathematica { _ =>
    val tactic = TactixLibrary.implyR(1) & TactixLibrary.andL(-1) & ArithmeticSimplification.smartHide
    val result = proveBy("x=1 & y=1 -> x=1".asFormula, tactic)
    result.subgoals(0).ante shouldBe result.subgoals(0).succ
  }

  it should "not throw away transitivity info" in withMathematica { _ =>
    val tactic = TactixLibrary.implyR(1) & SaturateTactic(andL('L)) & ArithmeticSimplification.smartHide
    val goal = "x=y & y=z & z > 0 -> x>0".asFormula
    val result = proveBy(goal, tactic)
    result.subgoals(0).ante.length shouldBe 3
    proveBy(goal, tactic & TactixLibrary.QE) shouldBe 'proved
  }

  it should "forget useless stuff" in withMathematica { _ =>
    val tactic = TactixLibrary.implyR(1) & SaturateTactic(andL('L)) & ArithmeticSimplification.smartHide
    val goal = "x>y & y>z & a > 0 & z > 0 -> x>0".asFormula
    val result = proveBy(goal, tactic)
    result.subgoals(0).ante.length shouldBe 3 //forget about a>0
    result.subgoals(0).ante.contains("a>0".asFormula) shouldBe false
    proveBy(goal, tactic & TactixLibrary.QE) shouldBe 'proved
  }

  it should "forget useless stuff about ODEs" in withMathematica { _ =>
    val tactic = TactixLibrary.implyR(1) & SaturateTactic(andL('L)) & ArithmeticSimplification.smartHide
    val goal = "x>y & y>z & a > 0 & z > 0 -> [{x'=1}]x>0".asFormula
    val result = proveBy(goal, tactic)
    result.subgoals should have size 1
    result.subgoals(0).ante.length shouldBe 3 //forget about a>0
    result.subgoals(0).ante shouldNot contain ("a>0".asFormula)
    proveBy(result.subgoals(0), diffInvariant("x>0".asFormula)(1) & dW(1) & QE) shouldBe 'proved
  }

  "smartSuccHide" should "simplify x=1 ==> x-1,y=1 to x=1 ==> x=1" in {
    val tactic = implyR(1) & orR(1) & ArithmeticSimplification.smartSuccHide
    val result = proveBy("x=1 -> x=1 | y=1".asFormula, tactic)
    result.subgoals(0).succ shouldBe result.subgoals(0).ante
  }

  it should "simplify x=1 ==> x-1,y=x to x=1 ==> x=1" in {
    val tactic = implyR(1) & orR(1) & ArithmeticSimplification.smartSuccHide
    val result = proveBy("x=1 -> x=1 | y=x".asFormula, tactic)
    result.subgoals(0).succ shouldBe result.subgoals(0).ante
  }

  it should "simplify x=1 ==> x-1,[y'=1}y>0 to x=1 ==> x=1" in {
    val tactic = implyR(1) & orR(1) & ArithmeticSimplification.smartSuccHide
    val result = proveBy("x=1 -> x=1 | [{y'=1}]y>0".asFormula, tactic)
    result.subgoals should have size 1
    result.subgoals.head.ante should contain only "x=1".asFormula
    result.subgoals.head.succ should contain only "x=1".asFormula
  }

  "replaceTransform" should "work in the antecedent" in withMathematica { _ =>
    proveBy("t<=ep & v>=0 & x>=x_0+v*ep -> x>=x_0+v*t".asFormula,
      prop & transformEquality("ep=t".asFormula)(-3) & id) shouldBe 'proved
  }

  it should "work in the succedent" in withMathematica { _ =>
    proveBy("t<=ep & v>=0 & x>=x_0+v*ep -> a<5 | x>=x_0+v*t | b<7".asFormula,
      prop & transformEquality("t=ep".asFormula)(2) & id) shouldBe 'proved
  }

  "absQE" should "prove abs(x-y)>=t -> abs(x-y+0)>=t+0" in withMathematica { _ =>
    proveBy("abs(x-y)>=t ==> abs(x-y+0)>=t+0".asSequent, ArithmeticSpeculativeSimplification.proveOrRefuteAbs) shouldBe 'proved
  }

  it should "prove abs(x-y)>=t -> abs(x-y)>=t+0" in withMathematica { _ =>
    proveBy("abs(x-y)>=t ==> abs(x-y)>=t+0".asSequent, ArithmeticSpeculativeSimplification.proveOrRefuteAbs) shouldBe 'proved
  }

  it should "prove a Robix example" in withMathematica { _ =>
    //todo: occasionally fails in 'assertNoCEX'. appears to be related to Mathematica
    val fml = "A>=0 & B>0 & V()>=0 & ep>0 & v_0>=0 & -B<=a & a<=A & abs(x_0-xo_0)>v_0^2/(2*B)+V()*v_0/B+(A/B+1)*(A/2*ep^2+ep*(v_0+V())) & -t*V()<=xo-xo_0 & xo-xo_0<=t*V() & v=v_0+a*t & -t*(v-a/2*t)<=x-x_0 & x-x_0<=t*(v-a/2*t) & t>=0 & t<=ep & v>=0 -> abs(x-xo)>v^2/(2*B)+V()*(v/B)".asFormula
    val tactic = SaturateTactic(alphaRule) &
      replaceTransform("ep".asTerm, "t".asTerm)(-8, "abs(x_0-xo_0)>v_0^2/(2*B)+V()*v_0/B+(A/B+1)*(A/2*ep^2+ep*(v_0+V()))".asFormula) &
      ArithmeticSpeculativeSimplification.proveOrRefuteAbs

    proveBy(fml, tactic) shouldBe 'proved
  }

  "exhaustiveAbsSplit" should "handle nested abs" in withMathematica { _ =>
    proveBy("abs(abs(x)-y)>=0".asFormula, ArithmeticSpeculativeSimplification.exhaustiveAbsSplit).subgoals shouldBe
      "x>=0, x-y>=0 ==> x-y>=0".asSequent :: "x < 0, -x-y>=0 ==> -x-y>=0".asSequent ::
      "x>=0, x-y < 0 ==> -(x-y)>=0".asSequent :: "x < 0, -x-y < 0 ==> -(-x-y)>=0".asSequent :: Nil
  }

  "autoTransform" should "transform formulas from worst-case bounds to concrete variables" in withMathematica { _ =>
    val s = "A>=0, B>0, V()>=0, ep>0, v_0>=0, -B<=a, a<=A, abs(x_0-xo_0)>v_0^2/(2*B)+V()*v_0/B+(A/B+1)*(A/2*ep^2+ep*(v_0+V())), -t*V()<=xo-xo_0, xo-xo_0<=t*V(), v=v_0+a*t, -t*(v-a/2*t)<=x-x_0, x-x_0<=t*(v-a/2*t), t>=0, t<=ep, v>=0 ==> abs(x-xo)>v^2/(2*B)+V()*(v/B)".asSequent
    proveBy(s, ArithmeticSpeculativeSimplification.autoMonotonicityTransform) shouldBe "A>=0, B>0, V()>=0, ep>0, v_0>=0, -B<=a, a<=A, abs(x_0-xo_0)>v_0^2/(2*B)+V()*v_0/B+(A/B+1)*(A/2*t^2+t*(v_0+V())), -t*V()<=xo-xo_0, xo-xo_0<=t*V(), v=v_0+a*t, -t*(v-a/2*t)<=x-x_0, x-x_0<=t*(v-a/2*t), t>=0, t<=t, v>=0 ==> abs(x-xo)>v^2/(2*B)+V()*(v/B)".asSequent
  }

  it should "transform formulas from worst-case bounds to concrete variables (2)" in withMathematica { _ =>
    val s = "A()>0, -c*(v_0+a*c-a/2*c)<=y-y_0, y-y_0<=c*(v_0+a*c-a/2*c), B()>=b(), dx^2+dy^2=1, b()>0, c>=0, ep()>0, v_0+a*c>=0, c<=ep(), lw()>0, v_0>=0, r_0!=0, abs(y_0-ly())+v_0^2/(2*b()) < lw(), abs(y_0-ly())+v_0^2/(2*b())+(A()/b()+1)*(A()/2*ep()^2+ep()*v_0) < lw(), -B()<=a, a<=A(), r!=0\n  ==>  abs(y-ly())+(v_0+a*c)^2/(2*b()) < lw()".asSequent
    proveBy(s, ArithmeticSpeculativeSimplification.autoMonotonicityTransform) shouldBe "A()>0, -c*(v_0+a*c-a/2*c)<=y-y_0, y-y_0<=c*(v_0+a*c-a/2*c), B()>=b(), dx^2+dy^2=1, b()>0, c>=0, ep()>0, v_0+a*c>=0, c<=c, lw()>0, v_0>=0, r_0!=0, abs(y_0-ly())+v_0^2/(2*b()) < lw(), abs(y_0-ly())+v_0^2/(2*b())+(A()/b()+1)*(A()/2*c^2+c*v_0) < lw(), -B()<=a, a<=A(), r!=0 ==> abs(y-ly())+(v_0+a*c)^2/(2*b()) < lw()".asSequent
  }

  "Sign analysis" should "compute seed from antecedent" in {
    val s = Sequent(IndexedSeq("A>=0".asFormula, "-B<0".asFormula, "v^2<=2*B*(m-x)".asFormula, "A<0".asFormula, "2*C-C^2>=0".asFormula), IndexedSeq("x<=m".asFormula))
    SignAnalysis.seedSigns(s) shouldBe Map(
      "A".asVariable -> Map(Sign.Pos0 -> Set(SeqPos(-1)), Sign.Neg0 -> Set(SeqPos(-4))),
      "B".asVariable -> Map(Sign.Pos0 -> Set(SeqPos(-2))),
      "2*B*(m-x)-v^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "v^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "2*B*(m-x)".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "2*B".asTerm -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "m-x".asTerm -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "v".asVariable -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "m".asVariable -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "x".asVariable -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3), SeqPos(-5))),
      "2*C-C^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-5))),
      "C^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-5))),
      "2*C".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-5))),
      "C".asVariable -> Map(Sign.Pos0 -> Set(SeqPos(-5)))
    )
  }

  it should "aggregate signs from seed" in {
    val s = Sequent(IndexedSeq("A>=0".asFormula, "-B<0".asFormula, "v^2<=2*B*(m-x)".asFormula, "A<0".asFormula, "2*C-C^2>=0".asFormula), IndexedSeq("x<=m".asFormula))
    SignAnalysis.aggregateSigns(SignAnalysis.seedSigns(s)) shouldBe Map(
      "A".asVariable -> Map(Sign.Pos0 -> Set(SeqPos(-1)), Sign.Neg0 -> Set(SeqPos(-4))),
      "B".asVariable -> Map(Sign.Pos0 -> Set(SeqPos(-2))),
      "2*B*(m-x)-v^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "v^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "2*B*(m-x)".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "2*B".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "m-x".asTerm -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "v".asVariable -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "m".asVariable -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "x".asVariable -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3), SeqPos(-5))),
      "2*C-C^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-5))),
      "C^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-5))),
      "2*C".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-5))),
      "C".asVariable -> Map(Sign.Pos0 -> Set(SeqPos(-5)))
      )
  }

  it should "pushDown signs from seed after aggregation" taggedAs KeYmaeraXTestTags.IgnoreInBuildTest in {
    val s = Sequent(IndexedSeq("A>=0".asFormula, "-B<0".asFormula, "v^2<=2*B*(m-x)".asFormula, "A<0".asFormula, "2*C-C^2>=0".asFormula), IndexedSeq("x<=m".asFormula))
    val seed = SignAnalysis.seedSigns(s)
    println("Seed\n" + seed.mkString("\n"))
    SignAnalysis.pushDownSigns(SignAnalysis.aggregateSigns(seed)) shouldBe Map(
      "A".asVariable -> Map(Sign.Pos0 -> Set(SeqPos(-1)), Sign.Neg0 -> Set(SeqPos(-4))),
      "B".asVariable -> Map(Sign.Pos0 -> Set(SeqPos(-2)/*, SeqPos(-3)*/)),
      "2*B*(m-x)-v^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "v^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "2*B*(m-x)".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "2*B".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "m-x".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "v".asVariable -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "m".asVariable -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "x".asVariable -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3), SeqPos(-5))),
      "2*C-C^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-5))),
      "C^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-5))),
      "2*C".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-5))),
      "C".asVariable -> Map(Sign.Pos0 -> Set(SeqPos(-5)))
    )
  }

  it should "pingpong until fixpoint" taggedAs KeYmaeraXTestTags.IgnoreInBuildTest in {
    val s = Sequent(IndexedSeq("A>=0".asFormula, "-B<0".asFormula, "v^2<=2*B*(m-x)".asFormula, "A<0".asFormula, "2*C-C^2>=0".asFormula), IndexedSeq("x<=m".asFormula))
    SignAnalysis.computeSigns(s) shouldBe Map(
      "A".asVariable -> Map(Sign.Pos0 -> Set(SeqPos(-1)), Sign.Neg0 -> Set(SeqPos(-4))),
      "B".asVariable -> Map(Sign.Pos0 -> Set(SeqPos(-2))),
      "2*B*(m-x)-v^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "v^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "2*B*(m-x)".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "2*B".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "m-x".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3))),
      "v".asVariable -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "m".asVariable -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "x".asVariable -> Map(Sign.Unknown -> Set(SeqPos(-3))),
      "2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-3), SeqPos(-5))),
      "2*C-C^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-5))),
      "C^2".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-5))),
      "2*C".asTerm -> Map(Sign.Pos0 -> Set(SeqPos(-5))),
      "C".asVariable -> Map(Sign.Pos0 -> Set(SeqPos(-5)))
    )
  }

  it should "work on the Robix example" in {
    val fml = "A>=0 & B>0 & V()>=0 & ep>0 & v_0>=0 & -B<=a & a<=A & abs(x_0-xo_0)>v_0^2/(2*B)+V()*v_0/B+(A/B+1)*(A/2*ep^2+ep*(v_0+V())) & -t*V()<=xo-xo_0 & xo-xo_0<=t*V() & v=v_0+a*t & -t*(v-a/2*t)<=x-x_0 & x-x_0<=t*(v-a/2*t) & t>=0 & t<=ep & v>=0 -> v=0|abs(x-xo)>v^2/(2*B)+V()*(v/B)".asFormula
    val s = proveBy(fml, SaturateTactic(alphaRule))
    // only check atoms
    val signs = SignAnalysis.computeSigns(s.subgoals.head)
    println(signs)
    signs("A".asVariable).keySet should contain only Sign.Pos0
    signs("B".asVariable).keySet should contain only Sign.Pos0
    signs("V()".asTerm).keySet should contain only Sign.Pos0
    signs("v_0".asVariable).keySet should contain only Sign.Pos0
    signs("t".asVariable).keySet should contain only Sign.Pos0
    signs("v".asVariable).keySet should contain only Sign.Pos0
    signs("ep".asVariable).keySet should contain only Sign.Pos0
    signs("a".asVariable).keySet should contain only Sign.Unknown
    signs("xo".asVariable).keySet should contain only Sign.Unknown
    signs("xo_0".asVariable).keySet should contain only Sign.Unknown
    signs("x".asVariable).keySet should contain only Sign.Unknown
  }

  it should "work on another Robix example" in withMathematica { _ =>
    val fml = "A>=0 & B>0 & V()>=0 & ep>0 & v_0>=0 & -B<=a & a<=A & abs(x_0-xo_0)>v_0^2/(2*B)+V()*v_0/B+(A/B+1)*(A/2*ep^2+ep*(v_0+V())) & -t*V()<=xo-xo_0 & xo-xo_0<=t*V() & v=v_0+a*t & -t*(v-a/2*t)<=x-x_0 & x-x_0<=t*(v-a/2*t) & t>=0 & t<=ep & v>=0 -> v=0|abs(x-xo)>v^2/(2*B)+V()*(v/B)".asFormula
    val tactic = SaturateTactic(alphaRule) &
      replaceTransform("ep".asTerm, "t".asTerm)(-8, s"abs(x_0-xo_0)>v_0^2/(2*B)+V()*v_0/B+(A/B+1)*(A/2*ep^2+ep*(v_0+V()))".asFormula) &
      abs(2, 0::Nil) & abs(-8, 0::Nil) & orL(-18) & OnAll(orL(-17)) &
      OnAll(SaturateTactic(andL('_))) & OnAll(SaturateTactic(exhaustiveEqL2R(hide=true)('L)))
    val s = proveBy(fml, tactic)
    // only check atoms
    val signs = SignAnalysis.computeSigns(s.subgoals.head)
    signs("A".asVariable).keySet should contain only Sign.Pos0
    signs("B".asVariable).keySet should contain only Sign.Pos0
    signs("V()".asTerm).keySet should contain only Sign.Pos0
    signs("v_0".asVariable).keySet should contain only Sign.Pos0
    signs("t".asVariable).keySet should contain only Sign.Pos0
    signs("ep".asVariable).keySet should contain only Sign.Pos0
    signs("a".asVariable).keySet should contain only Sign.Unknown
    signs("xo".asVariable).keySet should contain only Sign.Unknown
    signs("xo_0".asVariable).keySet should contain only Sign.Unknown
    signs("x".asVariable).keySet should contain only Sign.Unknown

    signs("x-xo".asTerm).keySet should contain only Sign.Pos0
    signs("x_0-xo_0".asTerm).keySet should contain only Sign.Pos0
  }

  "Bound computation" should "compute wanted bounds from succedent" in {
    val s = Sequent(IndexedSeq("A>=0".asFormula, "-B<0".asFormula, "v^2<=2*B*(m-x)".asFormula), IndexedSeq("x+5<=m".asFormula, "v^2<=2*B*(m-x)".asFormula))
    val signs = SignAnalysis.computeSigns(s)
    SignAnalysis.bounds(s.succ, signs, SuccPos) shouldBe Map(
      SeqPos(1) -> Map(
        "x".asVariable -> Map(Bound.Upper -> Set()),
        "m".asVariable -> Map(Bound.Lower -> Set()),
        "5".asTerm -> Map(Bound.Exact -> Set())),
      SeqPos(2) -> Map(
        "x".asVariable -> Map(Bound.Upper -> Set(SeqPos(-3))),
        "m".asVariable -> Map(Bound.Lower -> Set(SeqPos(-3))),
        "B".asVariable -> Map(Bound.Upper -> Set(SeqPos(-3))),
        "v".asVariable -> Map(Bound.Unknown -> Set()),
        "2".asTerm -> Map(Bound.Exact -> Set()))
    )
  }

  it should "work on a Robix example" in withMathematica { _ =>
    val fml = "A>=0 & B>0 & V()>=0 & ep>0 & v_0>=0 & -B<=a & a<=A & abs(x_0-xo_0)>v_0^2/(2*B)+V()*v_0/B+(A/B+1)*(A/2*ep^2+ep*(v_0+V())) & -t*V()<=xo-xo_0 & xo-xo_0<=t*V() & v=v_0+a*t & -t*(v-a/2*t)<=x-x_0 & x-x_0<=t*(v-a/2*t) & t>=0 & t<=ep & v>=0 -> abs(x-xo)>v^2/(2*B)+V()*(v/B)".asFormula
    val tactic = SaturateTactic(alphaRule) &
      replaceTransform("ep".asTerm, "t".asTerm)(-8, s"abs(x_0-xo_0)>v_0^2/(2*B)+V()*v_0/B+(A/B+1)*(A/2*ep^2+ep*(v_0+V()))".asFormula) &
      abs(1, 0::Nil) & abs(-8, 0::Nil) & orL(-18) & OnAll(orL(-17)) &
      OnAll(SaturateTactic(andL('_))) & OnAll(SaturateTactic(exhaustiveEqL2R(hide=true)('L)))
    val s = proveBy(fml, tactic)
    val signs = SignAnalysis.computeSigns(s.subgoals.head)
    val bounds = SignAnalysis.bounds(s.subgoals.head.succ, signs, SuccPos)
    println(bounds.mkString("\n"))
    bounds(SeqPos(1).asInstanceOf[SuccPos]) should contain theSameElementsAs Map(
      "x".asVariable -> Map(Bound.Lower -> Set()),
      "xo".asVariable -> Map(Bound.Upper -> Set()),
      "a".asVariable -> Map(Bound.Upper -> Set(SeqPos(-13))),
      "V()".asTerm -> Map(Bound.Upper -> Set(SeqPos(-3))),
      "v_0".asVariable -> Map(Bound.Upper -> Set(SeqPos(-15))),
      "B".asVariable -> Map(Bound.Lower -> Set(SeqPos(-15))),
      "t".asVariable -> Map(Bound.Upper -> Set(SeqPos(-13))),
      "1".asTerm -> Map(Bound.Exact -> Set()),
      "2".asTerm -> Map(Bound.Exact -> Set())
    )
  }

  "Bounds hiding" should "find formulas with non-matching bounds" in withMathematica { _ =>
    val s = Sequent(IndexedSeq("v>=0".asFormula, "-B<0".asFormula, "v^2<=2*B*(m-x)".asFormula, "v<0".asFormula, "x>m".asFormula, "2*C-C^2>=0".asFormula), IndexedSeq("x<=m".asFormula))

    proveBy(s, QE) shouldBe 'proved

    SignAnalysis.boundHideCandidates(s) should contain only SeqPos(-5)
  }

  it should "find formulas with non-matching bounds (2)" in withMathematica { _ =>
    val s = Sequent(IndexedSeq("-t*V <= x-x_0".asFormula, "x-x_0 <= t*V".asFormula, "V>=0".asFormula, "t>=0".asFormula), IndexedSeq("x-xo >= 2*V*t".asFormula))

    SignAnalysis.boundHideCandidates(s) should contain only SeqPos(-2)
  }

  it should "not choke on !=" in withMathematica { _ =>
    val s = Sequent(IndexedSeq("-t*V <= x-x_0".asFormula, "x-x_0 <= t*V".asFormula, "V>=0".asFormula, "t>=0".asFormula, "r!=0".asFormula), IndexedSeq("x-xo >= 2*V*t".asFormula))

    SignAnalysis.boundHideCandidates(s) should contain only SeqPos(-2)
  }

  it should "hide formulas with non-matching bounds" in withMathematica { _ =>
    val s = Sequent(IndexedSeq("v>=0".asFormula, "-B<0".asFormula, "v^2<=2*B*(m-x)".asFormula, "v<0".asFormula, "x>m".asFormula, "2*C-C^2>=0".asFormula), IndexedSeq("x<=m".asFormula))

    proveBy(s, QE) shouldBe 'proved

    val boundHidden = proveBy(s, ArithmeticSpeculativeSimplification.hideNonmatchingBounds)
    boundHidden.subgoals should have size 1
    boundHidden.subgoals.head.ante should contain theSameElementsAs List("v>=0".asFormula, "-B<0".asFormula, "v^2<=2*B*(m-x)".asFormula, "v<0".asFormula, "2*C-C^2>=0".asFormula)
    boundHidden.subgoals.head.succ should contain only "x<=m".asFormula

    proveBy(boundHidden.subgoals.head, ArithmeticSpeculativeSimplification.speculativeQE) shouldBe 'proved
  }

  it should "work on a Robix example" in withMathematica { _ =>
    val fml = "A>=0 & B>0 & V()>=0 & ep>0 & v_0>=0 & -B<=a & a<=A & abs(x_0-xo_0)>v_0^2/(2*B)+V()*v_0/B+(A/B+1)*(A/2*ep^2+ep*(v_0+V())) & -t*V()<=xo-xo_0 & xo-xo_0<=t*V() & v=v_0+a*t & -t*(v-a/2*t)<=x-x_0 & x-x_0<=t*(v-a/2*t) & t>=0 & t<=ep & v>=0 -> abs(x-xo)>v^2/(2*B)+V()*(v/B)".asFormula
    val tactic = SaturateTactic(alphaRule) &
      replaceTransform("ep".asTerm, "t".asTerm)(-8, s"abs(x_0-xo_0)>v_0^2/(2*B)+V()*v_0/B+(A/B+1)*(A/2*ep^2+ep*(v_0+V()))".asFormula) &
      abs(1, 0::Nil) & abs(-8, 0::Nil) & orL(-18) & OnAll(orL(-17)) &
      OnAll(SaturateTactic(andL('_))) & OnAll(SaturateTactic(exhaustiveEqL2R(hide=true)('L)))

    //@todo hideNonmatchingBounds does not yet work on the "middle" abs branches
    val s = proveBy(fml, tactic <(ArithmeticSpeculativeSimplification.speculativeQE, skip, skip, ArithmeticSpeculativeSimplification.speculativeQE))
    s.subgoals should have size 2
  }

  "Sign hiding" should "find inconsistent sign formulas" in {
    val s = Sequent(IndexedSeq("v>=0".asFormula, "-B<0".asFormula, "v^2<=2*B*(m-x)".asFormula, "v<0".asFormula, "x>m".asFormula, "2*C-C^2>=0".asFormula), IndexedSeq("x<=m".asFormula))

    SignAnalysis.signHideCandidates(s) should contain theSameElementsAs List(SeqPos(-2), SeqPos(-3), SeqPos(-5), SeqPos(-6), SeqPos(1))
  }

  it should "hide inconsistent sign formulas" in withMathematica { _ =>
    val s = Sequent(IndexedSeq("v>=0".asFormula, "-B<0".asFormula, "v^2<=2*B*(m-x)".asFormula, "v<0".asFormula, "x>m".asFormula, "2*C-C^2>=0".asFormula), IndexedSeq("x<=m".asFormula))

    proveBy(s, ArithmeticSpeculativeSimplification.hideInconsistentSigns & QE) shouldBe 'proved
  }

  "Multiple hidings together" should "figure it out" in withMathematica { _ =>
    val s = Sequent(IndexedSeq("v>=0".asFormula, "-B<0".asFormula, "v^2<=2*B*(m-x)".asFormula, "v<0".asFormula, "x>m".asFormula, "2*C-C^2>=0".asFormula), IndexedSeq("x<=m".asFormula))

    val boundHidden = proveBy(s, ArithmeticSpeculativeSimplification.hideNonmatchingBounds)
    boundHidden.subgoals should have size 1
    boundHidden.subgoals.head.ante should contain theSameElementsAs List("v>=0".asFormula, "-B<0".asFormula, "v^2<=2*B*(m-x)".asFormula, "v<0".asFormula, "2*C-C^2>=0".asFormula)
    boundHidden.subgoals.head.succ should contain only "x<=m".asFormula

    val smartHidden = proveBy(boundHidden.subgoals.head, smartHide)
    smartHidden.subgoals should have size 1
    smartHidden.subgoals.head.ante should contain theSameElementsAs List("v>=0".asFormula, "-B<0".asFormula, "v^2<=2*B*(m-x)".asFormula, "v<0".asFormula)
    smartHidden.subgoals.head.succ should contain only "x<=m".asFormula

    proveBy(smartHidden.subgoals.head, QE) shouldBe 'proved
  }
}
