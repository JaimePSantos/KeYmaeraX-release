/**
  * Copyright (c) Carnegie Mellon University. CONFIDENTIAL
  * See LICENSE.txt for the conditions of this license.
  */
package edu.cmu.cs.ls.keymaerax.btactics

import edu.cmu.cs.ls.keymaerax.bellerophon._
import edu.cmu.cs.ls.keymaerax.btactics.Idioms.?
import edu.cmu.cs.ls.keymaerax.btactics.TacticFactory._
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.infrastruct.Augmentors._
import edu.cmu.cs.ls.keymaerax.parser.Parser
import edu.cmu.cs.ls.keymaerax.pt.ProvableSig
import edu.cmu.cs.ls.keymaerax.infrastruct.Augmentors.SequentAugmentor
import edu.cmu.cs.ls.keymaerax.btactics.InvariantGenerator.GenProduct
import edu.cmu.cs.ls.keymaerax.infrastruct.{Augmentors, DependencyAnalysis, PosInExpr, Position}
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.collection.immutable.List
import util.control.Breaks._

/**
  * Invariant proof automation with generators.
  *
  * @author Andre Platzer
  */
object InvariantProvers {
  import Generator.Generator
  import TactixLibrary._

  private val logger = LoggerFactory.getLogger(getClass) //@note instead of "with Logging" to avoid cyclic dependencies

  /** loopSR: cleverly prove a property of a loop automatically by induction, trying hard to generate loop invariants.
    * Uses [[SearchAndRescueAgain]] to avoid repetitive proving.
    * @see [[loopauto]]
    * @see Andre Platzer. [[https://doi.org/10.1007/s10817-016-9385-1 A complete uniform substitution calculus for differential dynamic logic]]. Journal of Automated Reasoning, 59(2), pp. 219-266, 2017.
    *      Example 32. */
  def loopSR(gen: Generator[GenProduct]): DependentPositionTactic = anon ((pos:Position,seq:Sequent) => Augmentors.SequentAugmentor(seq)(pos) match {
    case loopfml@Box(prog, post) =>
      val cand: Iterator[Formula] = gen(seq, pos).iterator.map(_._1)
      val bounds: List[Variable] =
        if (StaticSemantics.freeVars(post).toSet.exists( v => v.isInstanceOf[DifferentialSymbol] ) )
          StaticSemantics.boundVars(loopfml).toSet.toList
        else DependencyAnalysis.dependencies(prog, DependencyAnalysis.freeVars(post))._1.toList
      var i = -1
      val subst: USubst = if (bounds.length==1)
        USubst(Seq(SubstitutionPair(DotTerm(), bounds.head)))
      else
        USubst(bounds.map(xi=> {i=i+1; SubstitutionPair(DotTerm(Real,Some(i)), xi)}))
      val jj: Formula = Parser.parser.formulaParser("jjl(" + subst.subsDefsInput.map(sp=>sp.what.prettyString).mkString(",") + ")")
      SearchAndRescueAgain(jj :: Nil,
        loop(subst(jj))(pos) < (nil, nil, chase(pos) & OnAll(
          Idioms.doIf(
            (pr: ProvableSig) => pr.subgoals.headOption.exists(_.sub(pos ++ PosInExpr(1::Nil)) match {
              case Some(Box(_, _)) => true
              case _ => false
            }))(
            //@todo chase will not always make progress, e.g., in [...][x:=x+1;][x'=2]P
            chase(pos ++ PosInExpr(1::Nil))
          )
        )),
        feedOneAfterTheOther(cand),
        //@todo switch to quickstop mode
        OnAll(master()) & done
      )
    case e => throw new TacticInapplicableFailure("Wrong shape to generate an invariant for " + e + " at position " + pos)
  })

  private def feedOneAfterTheOther[A<:Expression](gen: Iterator[A]) : (ProvableSig,ProverException)=>Seq[Expression] = {
    (_,e) => logger.debug("SnR loop status " + e)
      if (gen.hasNext)
        gen.next() :: Nil
      else
        throw new BelleNoProgress("loopSR ran out of loop invariant candidates")
  }



  /** [[TactixLibrary.loopPostMaster()]]. */
  def loopPostMaster(gen: Generator[GenProduct]): DependentPositionTactic = anon ((pos:Position,seq:Sequent) => Augmentors.SequentAugmentor(seq)(pos) match {
    case loopfml@Box(prog, post) =>
      // extra information occasionally thrown in to help direct invariant generation
      val initialCond = seq.ante.reduceRightOption(And).getOrElse(True)
      //@note all variables since substitution disallows introducing free variables unless proved
      val allVars: List[Variable] =
        // DependencyAnalysis is incorrect when primed symbols occur, so default to all variables in that case
        if (StaticSemantics.freeVars(post).toSet.exists(v => v.isInstanceOf[DifferentialSymbol]))
          (StaticSemantics.boundVars(loopfml) ++ StaticSemantics.freeVars(loopfml)).toSet.toList
        else
          //@todo does not work: DependencyAnalysis.dependencies(prog, DependencyAnalysis.freeVars(post))._1.toList
          (StaticSemantics.boundVars(loopfml) ++ StaticSemantics.freeVars(loopfml)).toSet.toList.
            filterNot(v => v.isInstanceOf[DifferentialSymbol])
      val subst: USubst = USubst(allVars.zipWithIndex.map({ case (v, i) => SubstitutionPair(DotTerm(Real, Some(i)), v) }))

      /** name(args) */
      def constructPred(name: String, args: Seq[Term]): Formula = {
        val head :: tail = args.reverse
        val arg = tail.foldLeft(head)({ case (ps, t) => Pair(t, ps) })
        PredOf(Function(name, None, arg.sort, Bool), arg)
      }

      val jj: Formula = constructPred("jjl", subst.subsDefsInput.map(_.what.asInstanceOf[Term]))
      val jjl: Formula = constructPred("jjl", subst.subsDefsInput.map(_.repl.asInstanceOf[Term]))
      // eventually instantiated to True, trick to substitute initialCond in during the search process
      val jja: Formula = PredOf(Function("jja", None, Unit, Bool), Nothing)

      /* stateful mutable candidate used in generateOnTheFly and the pass-through later since usubst end tactic not present yet */
      var candidate: Option[Formula] = Some(post)

      // completes ODE invariant proofs and arithmetic
      val finishOff: BelleExpr =
        OnAll(ifThenElse(DifferentialTactics.isODE,
          DifferentialTactics.mathematicaODE(pos) |
            // augment loop invariant to local ODE invariant if possible
            (anon ((pos: Position, seq: Sequent) => {
              val odePost = seq.sub(pos++PosInExpr(1::Nil))
              // no need to try same invariant again if odeInvariant(pos) already failed
              //@todo optimize: if the invariant generator were correct, could restrict to its first element
              ChooseSome(() => gen(seq, pos).iterator.map(_._1).filterNot(localInv => odePost.contains(localInv)),
                (localInv:Formula) => {
                  logger.debug("loopPostMaster local " + localInv)
                  DebuggingTactics.debug("local")&
                  dC(localInv)(pos) < (dW(pos) & QE(), DifferentialTactics.mathematicaODE(pos)) &
                  done & DebuggingTactics.debug("success")
                })
            }))(pos)
          ,
          QE()
        )(pos)) & done

      // invariant candidate iterators (avoid retrying same invariants over and over again when same assume-more-sequents are revisited)
      val generators: scala.collection.mutable.Map[Sequent, Iterator[Formula]] = scala.collection.mutable.Map.empty

      /** generate the next candidate from the given sequent of the given provable with the present candidate currentCandidate */
      def nextCandidate(pr: ProvableSig, sequent: Sequent, currentCandidate: Option[Formula]): Option[Formula] = currentCandidate match {
        //@note updates "global" candidates
        case Some(cand) =>
          logger.debug("loopPostMaster subst " + USubst(Seq(jjl ~>> cand, jja ~> True)))
          // plug in true for jja, commit if succeeded. Else plug in init for jja and generate
          val wouldBeSeq = USubst(Seq(jjl ~>> cand, jja ~> True))(sequent)
          lazy val wouldBeSubgoals = pr(USubst(Seq(jjl ~>> cand, jja ~> True)))
          logger.debug("loopPostMaster looks at\n" + wouldBeSeq)
          //@note first check induction step; then lazily check all subgoals (candidate may not be true initially or not strong enough)
          val stepProof = proveBy(wouldBeSeq, ?(finishOff))
          if (stepProof.isProved && proveBy(wouldBeSubgoals(stepProof, wouldBeSubgoals.subgoals.indexOf(stepProof.conclusion)), ?(finishOff)).isProved) {
            // proof will work so no need to change candidate
            logger.debug("Proof will work " + wouldBeSubgoals.prettyString)
            currentCandidate
          } else {
            logger.debug("loopPostMaster progressing")
            val assumeMoreSeq = USubst(Seq(jjl ~>> cand, jja ~> initialCond))(sequent)

            val generator = gen(assumeMoreSeq, pos).map(_._1)
            // keep iterating remembered iterator (otherwise generator restarts from the beginning)
            if (!generators.contains(assumeMoreSeq)) generators.put(assumeMoreSeq, generator.iterator)
            val candidates = generators(assumeMoreSeq)

            while (candidates.hasNext) {
              val next = Some(candidates.next())
              if (next != currentCandidate) {
                logger.debug("loopPostMaster next    " + next.get)
                return next
              }
            }
            None
          }
        case None => None
      }

      def generateOnTheFly[A <: Expression](pos: Position): (ProvableSig, ProverException) => scala.collection.immutable.Seq[Expression] = {
        logger.debug("loopPostMaster init " + candidate)
        (pr: ProvableSig, _: ProverException) => {
          var sawODE: Boolean = false
          //@note updates "global" candidate
          breakable {
            for (seq <- pr.subgoals) {
              seq.sub(pos) match {
                case Some(Box(_: ODESystem, _)) =>
                  sawODE = true
                  val next = nextCandidate(pr, seq, candidate)
                  // try the candidate if there is one, else proceed to the next branch
                  if (next.isDefined) {
                    candidate = next
                    break
                  }
                  logger.debug("loopPostMaster branch skip")
                case _ => // ignore branches that are not about ODEs
              }
            }
            candidate = None
          }
          candidate match {
            case Some(c) =>
              logger.debug("loopPostMaster cand    " + c)
              // c for jjl, eventual True for jja
              c :: True :: Nil
            case None =>
              if (sawODE)
                throw new BelleNoProgress("loopPostMaster: Invariant generator ran out of ideas for\n" + pr.prettyString)
              else
                throw new BelleNoProgress("loopPostMaster: No more progress for lack of ODEs in the loop\n" + pr.prettyString)
          }
        }
      }

      SearchAndRescueAgain(jjl :: jja :: Nil,
        loop(subst(jj))(pos) < (nil, nil,
          cut(jja) <(
            /* use jja() |- */
            chase(pos) & OnAll(unfoldProgramNormalize) & OnAll(
              Idioms.doIf(_.subgoals.headOption.exists(_.sub(pos ++ PosInExpr(1::Nil)) match {
                case Some(Box(_, _)) => true
                case _ => false
              }))(
                //@todo chase will not always make progress, e.g., in [...][x:=x+1;][x'=2]P
                chase(pos ++ PosInExpr(1::Nil))
              ) & ?(QE)
            )
            ,
            /* show |- jja() is postponed since only provable when eventually jja()~>True instantiated */
            cohide('Rlast, jja)
            )
          ),
        generateOnTheFly(pos)
        ,
        finishOff
      )

    case e => throw new TacticInapplicableFailure("Wrong shape to generate an invariant for " + e + " at position " + pos)
  })

}
