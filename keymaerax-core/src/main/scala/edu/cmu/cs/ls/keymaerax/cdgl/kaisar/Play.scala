/**
  * Copyright (c) Carnegie Mellon University.
  * See LICENSE.txt for the conditions of this license.
  */
/**
  * Interpreter for strategies
  * @author Brandon Bohrer
  */
package edu.cmu.cs.ls.keymaerax.cdgl.kaisar

import java.math.RoundingMode

import edu.cmu.cs.ls.keymaerax.cdgl.kaisar.KaisarProof.Ident
//import edu.cmu.cs.ls.keymaerax.cdgl.kaisar.Play.{, number, state}
import edu.cmu.cs.ls.keymaerax.core
import spire.math._
import edu.cmu.cs.ls.keymaerax.core._

/** Indicates that expression was not in the executable fragment */
case class UnsupportedStrategyException(nodeID: Int) extends Exception
/** Indicates that we tried to evaluate something whose value could not be determined */
case class NoValueException(nodeID: Int = -1) extends Exception
/** Indicates that a Demonic test failed, thus Demon loses */
case class TestFailureException(nodeID: Int) extends Exception

/** Runtime state of executed strategy */
class Environment[number <: Numeric[number, Ternary]] (val factory: NumberFactory[Ternary, number]) {
  /** Raw program variable state */
  var state: Play.state[number] = Map()

  /** Is variable dynamically defined? */
  def contains(x: Ident): Boolean = state.contains(x)

  /** Unindexed variable x always represents "current value," but indexed "x_i" also remembered for history. This sets both. */
  def set(x: Ident, v: number): Unit = {
    val base = x match {case bv: BaseVariable => Variable(bv.name) case ds: DifferentialSymbol => DifferentialSymbol(Variable(ds.name))}
    (state = state.+(x -> v).+(base -> v))
  }

  /** Get current state of value, which must be defined */
  def get(x: Ident): number = state(x)

//  private def ternaryCmp(l: => number, r: => number, op: (number, number) => Boolean)

  /** Evaluate decidable formula at current state */
  def holds(fml: Formula): Ternary = {
    fml match {
      case Less(l, r) => eval(l) < eval(r)
      case LessEqual(l, r) => eval(l) <= eval(r)
      case Greater(l, r) => eval(l) > eval(r)
      case GreaterEqual(l, r) => eval(l) >= eval(r)
      case Equal(l, r) => eval(l).eq(eval(r))
      case NotEqual(l, r) => eval(l).diseq(eval(r))
      // @TODO dangerous
      case Not(f) => !holds(f)
      case Or(l, r) => holds(l) || holds(r)
      case And(l, r) => holds(l) && holds(r)
      // @TODO dangerous
      case Imply (l, r) =>  holds(r) || !holds(l)
      case Equiv (l, r) =>  holds(r).iff(holds(l))
      case True => KnownTrue()
      case False => KnownFalse()
      case _ => throw NoValueException()
    }
  }

  /** Evaluate computable term at current state */
  def eval(f: Term): number = {
    f match {
      case n: core.Number => factory.number(n.value)
      case Plus(l, r) => eval(l) + eval(r)
      case Minus(l, r) => eval(l) - eval(r)
      case Times(l, r) => eval(l) * eval(r)
      case Neg(f) => - eval(f)
      case Divide(l, r) => eval(l) / eval(r)
      case Power(l, r: core.Number) if r.value.isValidInt =>
        val n = r.value.intValue()
        eval(l).pow(n, 1)
      // @TODO: Hack. For rational roots, convert rational to algebraic, compute root, convert back
      case Power(l, Divide(num: core.Number, denom: core.Number)) if num.value.isValidInt  && denom.value.isValidInt =>
        val v = eval(l)
        val n = num.value.intValue()
        val d = denom.value.intValue()
        v.pow(n, d)
      case FuncOf(f, args) if f.interpreted =>
        (f.name, args) match {
          case ("max", Pair(l, r)) => eval(l).max(eval(r))
          case ("min", Pair(l, r)) => eval(l).min(eval(r))
          case ("abs", l) => eval(l).abs
          case _ => throw NoValueException()
        }
      case v: Variable =>
        if (state.contains(v))
          state(v)
        else {
          println(s"Unknown value for variable $v")
          throw NoValueException()
        }
      case _ =>
        println(s"Couldn't evaluate $f in state $state")
        throw NoValueException()
    }
  }
}

object Play {
  type state[T] = Map[Ident, T]
  /** For printing,  etc. */
  val ROUNDING_SCALE = 5
}

/** Interpreter for strategies */
class Play[N <: Numeric[N, Ternary]] (factory: NumberFactory[Ternary, N ]) {
  type number = N
  val number: (Rational => number) = factory.number

  /** Interpret given angel strategy against given demon strategy */
  def apply(as: AngelStrategy, ds: DemonStrategy[number]): Environment[number] = {
    val env = new Environment(factory)
    // in-place updates to environment
    apply(env, as, ds)
    env
  }

  /** Interpret given angel strategy against given demon strategy in given environment. */
  def apply(env: Environment[number], as: AngelStrategy, ds: DemonStrategy[number]): Unit = {
    as match {
      case STest(f) =>
        try {
          if (env.holds(f) != KnownTrue()) {
            println(s"""Test \"$f\" failed in state ${env.state}""")
            throw TestFailureException(as.nodeID)
          }
        } catch {
          case v: NoValueException => throw NoValueException(as.nodeID)
        }
      case SAssign(x, f) =>
        try {
          val v = env.eval(f)
          ds.writeAssign(as.nodeID, x, v)
          //println(s"Interpreter assigned $x -> $v")
          env.set(x, v)
        } catch {
          case v: NoValueException => throw NoValueException(as.nodeID)
        }
      case SAssignAny(x) =>
        val v = ds.readAssign(as.nodeID, x)
        //println(s"Interpreter star-assigned $x -> $v")
        env.set(x, v)
      case SLoop(s) =>
        // in-place update
        while(ds.readLoop(as.nodeID)) {
          apply(env, s, ds)
        }
      case SCompose(children) =>
        // in-place update of env
        children.foreach(x => apply(env, x, ds))
      case SChoice(l, r) =>
        if (ds.readChoice(as.nodeID))
          apply(env, r, ds)
        else
          apply(env, l, ds)
      case _ => throw UnsupportedStrategyException(as.nodeID)
    }
  }
}
