package edu.cmu.cs.ls.keymaerax.btactics

import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import edu.cmu.cs.ls.keymaerax.btactics.macros.DerivationInfoAugmentors._
import edu.cmu.cs.ls.keymaerax.btactics.macros.ProvableInfo

import scala.collection.immutable

/**
 * Tests [[edu.cmu.cs.ls.keymaerax.btactics.ProofRuleTactics]]
 */
class ProofRuleTests extends TacticTestBase {

  "Axiomatic" should "support axiomatic rules" in withQE { _ =>
    val result = proveBy(
      Sequent(immutable.IndexedSeq("[a_;]p_(||)".asFormula), immutable.IndexedSeq("[a_;]q_(||)".asFormula)),
      TactixLibrary.by(ProvableInfo("[] monotone"), USubst(Nil)))
    result.subgoals should have size 1
    result.subgoals.head.ante should contain only "p_(||)".asFormula
    result.subgoals.head.succ should contain only "q_(||)".asFormula
  }

  it should "use the provided substitution for axiomatic rules" in withQE { _ =>
    val result = proveBy(
      Sequent(immutable.IndexedSeq("[?x>5;]x>2".asFormula), immutable.IndexedSeq("[?x>5;]x>0".asFormula)),
      TactixLibrary.by(ProvableInfo("[] monotone"),
        USubst(
          SubstitutionPair(ProgramConst("a_"), Test("x>5".asFormula))::
          SubstitutionPair("p_(||)".asFormula, "x>2".asFormula)::
          SubstitutionPair("q_(||)".asFormula, "x>0".asFormula)::Nil)))
    result.subgoals should have size 1
    result.subgoals.head.ante should contain only "x>2".asFormula
    result.subgoals.head.succ should contain only "x>0".asFormula
  }

  it should "support axioms" in withTactics {
    val result = proveBy(
      Sequent(immutable.IndexedSeq(), immutable.IndexedSeq("\\forall x_ x_>0 -> z>0".asFormula)),
      TactixLibrary.by(Ax.allInst,
        USubst(
          SubstitutionPair(PredOf(Function("p", None, Real, Bool), DotTerm()), Greater(DotTerm(), "0".asTerm))::
          SubstitutionPair("f()".asTerm, "z".asTerm)::Nil)))
    result shouldBe 'proved
  }

  it should "support derived axioms" in withTactics {
    val theSubst = USubst(SubstitutionPair(UnitPredicational("p_", AnyArg), Greater("x_".asVariable, "0".asTerm))::Nil)
    val theAxiom = Ax.notAll.provable

    val result = proveBy(
      Sequent(immutable.IndexedSeq(), immutable.IndexedSeq("(!\\forall x_ x_>0) <-> (\\exists x_ !x_>0)".asFormula)),
      TactixLibrary.by(Ax.notAll, //(!\forall x (p(||))) <-> \exists x (!p(||))
        theSubst))

    result shouldBe 'proved
  }
  import SequentCalculus._
  "hideR" should "hide sole formula in succedent" in withTactics {
    val result = proveBy("a=2".asFormula, hideR(1))
    result.subgoals should have size 1
    result.subgoals.head.ante shouldBe empty
    result.subgoals.head.succ shouldBe empty
  }

  it should "hide first formula in succedent" in withTactics {
    val result = proveBy(Sequent(immutable.IndexedSeq(), immutable.IndexedSeq("a=2".asFormula, "b=3".asFormula)),
      hideR(1))
    result.subgoals should have size 1
    result.subgoals.head.ante shouldBe empty
    result.subgoals.head.succ should contain only "b=3".asFormula
  }

  it should "hide last formula in succedent" in withTactics {
    val result = proveBy(Sequent(immutable.IndexedSeq(), immutable.IndexedSeq("a=2".asFormula, "b=3".asFormula)),
      hideR(2))
    result.subgoals should have size 1
    result.subgoals.head.ante shouldBe empty
    result.subgoals.head.succ should contain only "a=2".asFormula
  }
}
