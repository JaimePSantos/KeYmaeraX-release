/**
  * Copyright (c) Carnegie Mellon University.
  * See LICENSE.txt for the conditions of this license.
  */
/**
  * Parse programmatic syntax for Kaisar proofs
  * @author Brandon Bohrer
  */
package edu.cmu.cs.ls.keymaerax.cdgl.kaisar

import edu.cmu.cs.ls.keymaerax.cdgl.kaisar.Kaisar.{MAX_CHAR, result}
import fastparse.Parsed.Success
import fastparse._
// allow Scala-style comments and ignore newlines
import ScalaWhitespace._
import edu.cmu.cs.ls.keymaerax.cdgl.kaisar.KaisarProof._
import edu.cmu.cs.ls.keymaerax.cdgl.kaisar.ParserCommon._
import edu.cmu.cs.ls.keymaerax.cdgl.kaisar.ProofParser.atomicStatement
import edu.cmu.cs.ls.keymaerax.parser.{KeYmaeraXParser, Parser}
import edu.cmu.cs.ls.keymaerax.core._
import fastparse.Parsed.{Failure, TracedFailure}
import fastparse.internal.Msgs

import scala.collection.immutable.StringOps

object ParserCommon {
  val reservedWords: Set[String] = Set("by", "RCF", "auto", "prop", "end", "proof", "using", "let", "match", "print", "for", "while", "true", "false")
  // @TODO: Error messages for unexpected keywords
  // "left", "right", "ghost", "solve", "induct", "domain", "duration", "yield" "cases", "or", , "assert", "assume", "have", , "either"

  def identOrKeyword[_: P]: P[String] = {
    // Because (most of) the parser uses multiline whitespace, rep will allow space between repetitions.
    // locally import "no whitespace" so that identifiers cannot contain spaces.
    import NoWhitespace._
    (CharIn("a-zA-Z") ~ CharIn("a-zA-Z1-9").rep ~ P("'").?).!
  }

  def identString[_: P]: P[String] = {
    identOrKeyword.filter(s  => !reservedWords.contains(s))
  }
  def reserved[_: P]: P[String]  = CharIn("a-zA-Z'").rep(1).!.filter(reservedWords.contains)
  def ws[_ : P]: P[Unit] = P((" " | "\n").rep)
  def wsNonempty[_ : P]: P[Unit] = P((" " | "\n").rep(1))
  def literal[_: P]: P[String] = "\"" ~ CharPred(c => c != '\"').rep(1).! ~ "\""
  def ident[_: P]: P[Ident] = identString.map(c => if (c.endsWith("'")) DifferentialSymbol(BaseVariable(c.dropRight(1))) else BaseVariable(c))
  def number[_: P]: P[Number] = {
    import NoWhitespace._
    ("-".!.? ~  (("0" | CharIn("1-9") ~ CharIn("0-9").rep) ~ ("." ~ CharIn("0-9").rep(1)).?).!)
    .map({case (None, s) => Number(BigDecimal(s))
          case (Some("-"), s) => Number(BigDecimal("-" + s))})
  }

  def variableTrueFalse[_ : P]: P[Expression] = {
    identOrKeyword.map({
      case "true" =>
        True
      case "false" =>
        False
      case s => {
      if (s.endsWith("'")) DifferentialSymbol(BaseVariable(s.dropRight(1))) else
        BaseVariable(s)
    }}).filter({case BaseVariable(x, _, _) => !reservedWords.contains(x) case _ => true})
  }

  // hack:  ^d is reserved for dual programs, don't allow in terms
  def opPower[_: P]: P[Unit] =
    P("^") ~ !(P("d") ~ !(CharIn("a-zA-Z")))
}

object ExpressionParser {
  // for parsing extended kaisar expression + pattern syntax
  def wild[_: P]: P[FuncOf] = (Index ~ P("*")).
    map(i => FuncOf(Function("wild", domain = Unit, sort = Unit, interpreted = true), Nothing))

  private def nargs(args: Seq[Term]): Term = args match {case Nil => Nothing case _ => args.reduceRight[Term](Pair)}
  private def ntypes(args: Seq[Sort]): Sort = args match {case Nil => Unit case _ => args.reduceRight[Sort](Tuple)}
  def funcOf[_: P]: P[FuncOf] =
    // note: user-specified let definitions can have 0 args
    (ident ~ "(" ~ term.rep(min = 0, sep = ",") ~ ")").map({case (f, args) =>
      val builtins = Set("min", "max", "abs")
      val fn = Function(f.name, domain = ntypes(args.map(_ => Real)), sort = Real, interpreted = builtins.contains(f.name))
      FuncOf(fn, nargs(args))
    })

  def parenTerm[_: P]: P[Term] = (("(" ~/ term.rep(sep = ",", min = 1) ~/ ")")).map(ss =>
     if (ss.length == 1) ss.head
     else StandardLibrary.termsToTuple(ss.toList)
  )

  def variable[_: P]: P[Variable] = variableTrueFalse.filter(_.isInstanceOf[Variable]).map(_.asInstanceOf[Variable])

  def terminal[_: P]: P[Term] =
    ((funcOf | wild | variable | number | parenTerm) ~ P("'").!.?).
      map({case (e, None) => e case (e, Some(_)) => Differential(e)})

  def power[_: P]: P[Term] =
    (terminal ~ (opPower ~ terminal).rep()).
      map({case (x, xs) => (xs.+:(x)).reduce(Power)}).map(s => {s})

  def neg[_: P]: P[Term] =
    // check neg not followed by inverse ghost terminator or numeral since neg followed by numeral is Number() constructor already
    (("-".! ~ !(P("-/") | CharIn(">0123456889"))).? ~ power).map({case (None, e) => e case (Some(_), e) => Neg(e)})

  def labelRefArgs[_: P]: P[List[Term]] = {
    ("(" ~/ term.rep(sep = ",") ~/ ")").map(terms => terms.toList)
  }

  def labelRef[_: P]: P[LabelRef] = {
    (identString ~ labelRefArgs.?).map({case (lit, maybeArgs)  => LabelRef(lit, maybeArgs.getOrElse(Nil))})
  }

  def at[_: P]: P[Term] = {
    //val at = Function("at", domain = Tuple(Real, Unit), sort = Real, interpreted = true)
    (neg ~ (CharIn("@") ~ labelRef).?).map({
      case (e, None) => e
      case (e, Some(lr:LabelRef)) => makeAt(e, lr)
    })
  }

  def divide[_: P]: P[Term] =
    (at ~ (("/" | "*").! ~ at).rep).map({case (x: Term, xs: Seq[(String, Term)]) =>
      xs.foldLeft(x)({case (acc, ("/", e)) => Divide(acc, e) case (acc, ("*", e)) => Times(acc, e)})})

  def minus[_: P]: P[Term] =
    // disambiguate "-" and "->" and "--/"
    (divide ~ ((("-"  ~ !(P("-/") | P(">")) )| "+").! ~ divide).rep).map({case (x: Term, xs: Seq[(String, Term)]) =>
      xs.foldLeft(x)({case (acc, ("+", e)) => Plus(acc, e) case (acc, ("-", e)) => Minus(acc, e)})})

  def term[_: P]: P[Term] = minus

  // distinguish pattern terms for better error recovery
  private def isPatTerm(f: Term): Boolean = {
    f match {
      case Nothing => true
      case _: Variable => true
      case fo: FuncOf if fo.child.isInstanceOf[BaseVariable] => true
      case Pair(l, r) => isPatTerm(l) && isPatTerm(r)
      case _ => false
    }
  }

  def patTerm[_: P]: P[Term] = term.filter(isPatTerm).opaque("variable or tuple pattern x or (x1, ..., xn)")

  def test[_: P]: P[Test] = ("?" ~ formula).map(Test)
  // semicolon terminator parsed in differentialProduct
  def assign[_: P]: P[Assign] = (variable ~ ":=" ~ term).map({case (x, f) => Assign(x, f)})
  // semicolon terminator parsed in differentialProduct
  def assignAny[_: P]: P[AssignAny] = (variable ~ ":=" ~ ws ~ "*").map(AssignAny)

  def parenProgram[_: P]: P[Program] = "{" ~ program ~ "}".?

  // Note: Cut after = since x' could be either ode or assignment
  def atomicOde[_: P]: P[AtomicODE] = (P("") ~ variable.filter(_.isInstanceOf[DifferentialSymbol]) ~  "=" ~  term).
    map({case (x: DifferentialSymbol, f) => AtomicODE(x, f)})

  def programConst[_: P]: P[ProgramConst] = (identString ~ ";").map(ProgramConst(_))

  def terminalProgram[_: P]: P[Program] = (parenProgram | atomicOde | test  | assignAny | assign | programConst)

  // Note: ; needs to be optional since {} is an atomic program which doesn't need ; terminator. If we really care we could
  // do a flapmap and only use ; when not {}
  def differentialProduct[_: P]: P[Program] =
    // @TODO: Careful about polymorphic list
    ((terminalProgram.opaque("program") ~ ("," ~ terminalProgram.opaque("program")).rep ~ ("&" ~ formula).? ~ ";".?).map({
      case (x: AtomicODE, xs: Seq[_], Some(fml)) =>
        val odes: Seq[DifferentialProgram] = xs.map(_.asInstanceOf[DifferentialProgram]).+:(x)
        ODESystem(odes.reduce[DifferentialProgram]({case (l, r) => DifferentialProduct(l, r)}), fml)
      case (x: AtomicODE, xs: Seq[_], None) =>
        val odes: Seq[DifferentialProgram] = xs.map(_.asInstanceOf[DifferentialProgram]).+:(x)
        ODESystem(odes.reduce[DifferentialProgram]({case (l, r) => DifferentialProduct(l, r)}))
      case (x, Seq(), None) => x
    }) ~ ((P("*") | P("^d") | P("^@")).!.rep).opaque("postfix")).map({case (atom: Program, posts: Seq[String]) =>
      posts.foldLeft[Program](atom)({case (acc, "*") => Loop(acc) case (acc, ("^d" | "^@")) => Dual(acc)})
    })

  def compose[_: P]: P[Program] =
    (differentialProduct.rep(1)).map({xs => xs.reduceRight(Compose)})

  def choice[_: P]: P[Program] =
    (compose ~ ("++"  ~ compose).rep).map({case (x, xs) => (xs.+:(x)).reduceRight(Choice)})

  def program[_: P]: P[Program] = choice

  // !P  P & Q   P | Q   P -> Q   P <-> Q   [a]P  <a>P  f ~ g \forall \exists
  def infixTerminal[_: P]: P[Formula] =
    (term.opaque("term") ~ (("<=" | "<" | "=" | "!=" | ">=" | ">").!.opaque("cmp") ~ term.opaque("term"))).map({
      case (l, ("<=", r)) => LessEqual(l, r)
      case (l, ("<", r)) => Less(l, r)
      case (l, ("=", r)) => Equal(l, r)
      case (l, ("!=", r)) => NotEqual(l, r)
      case (l, (">", r)) => Greater(l, r)
      case (l, (">=", r)) => GreaterEqual(l, r)
      case (l, (s, r)) => True
    })

  // Note: need nocut outside because  delimiter ( is ambiguous, could be term e.g. (1) <= (2)
  def parenFormula[_: P]: P[Formula] = (("(" ~/ formula ~/ ")"))
  //def verum[_: P]: P[AtomicFormula] = P("true").map(_ => True)
  //def falsum[_: P]: P[AtomicFormula] = P("false").map(_ => False)
  def verumFalsum[_: P]: P[AtomicFormula] = variableTrueFalse.filter(_.isInstanceOf[AtomicFormula]).map(_.asInstanceOf[AtomicFormula])
  def not[_: P]: P[Formula] = ("!" ~ prefixTerminal).map(f => Not(f))
  def forall[_: P]: P[Formula] = ("\\forall" ~ variable  ~ prefixTerminal).map({case (x, p) => Forall(List(x), p)})
  def exists[_: P]: P[Formula] = ("\\exists" ~ variable  ~ prefixTerminal).map({case (x, p) => Exists(List(x), p)})
  def box[_: P]: P[Formula] = (("[" ~ program  ~ "]" ~ prefixTerminal)).map({case (l, r) => Box(l ,r)})
  def diamond[_: P]: P[Formula] = (("<" ~ !P("->"))  ~ program  ~ ">"  ~ prefixTerminal).map({case (l, r) => Diamond(l, r)})
  def predicate[_: P]: P[PredOf] =
  // note: user-specified let definitions can have 0 args
    (ident ~ "(" ~ term.rep(min = 0, sep = ",") ~ ")").map({case (f, args) =>
      val fn = Function(f.name, domain = ntypes(args.map(_ => Real)), sort = Bool)
      PredOf(fn, nargs(args))
    })


  def prefixTerminal[_: P]: P[Formula] =
    // predicate vs infixTerminal (with function term)  is ambiguous
    ((verumFalsum | not | forall | exists | box | diamond   | NoCut(infixTerminal) | predicate | parenFormula ) ~ ("'".!.?)).
      map({case (f, None) => f case (f, Some(_)) => DifferentialFormula(f)})

  def atFml[_: P]: P[Formula] = {
    (prefixTerminal ~ (CharIn("@") ~ labelRef).?).map({
      case (e, None) => e
      case (e, Some(lr:LabelRef)) => makeAt(e, lr)
    })
  }

  def and[_: P]: P[Formula] = (atFml ~ ("&"  ~ atFml).rep).map({case (x, xs) => (xs.+:(x)).reduceRight(And)})
  def or[_: P]: P[Formula] = (and ~ ("|"  ~ and).rep).map({case (x, xs) => (xs.+:(x)).reduceRight(Or)})
  def imply[_: P]: P[Formula] = (or ~ ("->"  ~ or).rep).map({case (x, xs) => (xs.+:(x)).reduceRight(Imply)})
  def equiv[_: P]: P[Formula] = (imply ~ ("<->"  ~ imply).rep).map({case (x, xs) => (xs.+:(x)).reduceRight(Equiv)})

  def formula[_: P]: P[Formula] = equiv
  // Term has to be last because of postfix operators
  // NoCut for infix comparisons and also predicate vs function
  def expression[_: P]: P[Expression] =  program | NoCut(formula) | term
}

object ProofParser {
  import ExpressionParser.{formula, program, term, expression, atomicOde}
  import KaisarKeywordParser.proofTerm

  def locate[T <: ASTNode](x:T, i: Int): T = {x.setLocation(i); x}

  def exhaustive[_: P]: P[Exhaustive] = P("by" ~ ws ~ Index ~ "exhaustion").map(i => locate(Exhaustive(), i))
  def rcf[_: P]: P[RCF] = P("by" ~ ws ~ Index ~ "RCF").map(i => locate(RCF(), i))
  def auto[_: P]: P[Auto] = P("by" ~ ws ~ Index ~ "auto").map(i => locate(Auto(), i))
  def prop[_: P]: P[Prop] = P("by" ~ ws ~ Index ~ "prop").map(i => locate(Prop(), i))
  def hypothesis[_: P]: P[Hypothesis] = P("by" ~ ws ~ Index ~ "hypothesis").map(i => locate(Hypothesis(), i))

  def solution[_: P]: P[Solution] = P("by" ~ ws ~ Index ~ "solution").map(i => locate(Solution(), i))
  def diffInduction[_: P]: P[DiffInduction] = P("by" ~ ws ~ Index ~ "induction").map(i => locate(DiffInduction(), i))
  def using[_: P]: P[Using] = (Index ~ P("using") ~ selector.rep  ~ rawMethod).
    map({case (i, sels, meth) => locate(Using(sels.toList, meth), i)})
  def byProof[_: P]: P[ByProof] = (Index ~ "proof" ~ proof ~ "end").
    map({case (i, pf) => locate(ByProof(pf), i)})

  def forwardSelector[_: P]: P[ForwardSelector] = proofTerm.map(ForwardSelector)
  def patternSelector[_: P]: P[PatternSelector] =
    (Index ~ P("*")).map(i => locate(PatternSelector(wild), i)) |
    (Index ~ term).map({case (i, e)  => locate(PatternSelector(e), i)})
  def defaultSelector[_: P]: P[DefaultSelector.type] = P("...").map(_ => DefaultSelector)
  def selector[_: P]: P[Selector] = !reserved ~ (forwardSelector | patternSelector | defaultSelector)

  def rawMethod[_: P]: P[Method] = auto | rcf |  prop | hypothesis | solution | diffInduction | exhaustive | using | byProof
  // If method has no selectors, then insert the "default" heuristic selection method
  // If terminator ; is seen instead of method, default to auto method
  def method[_: P]: P[Method] = (P(";").map(_ => None) | (rawMethod ~/ ";").map(Some(_))).map({
    case None => Using(List(DefaultSelector), Auto())
    case Some(u: Using) => u
    case Some(m) => Using(List(DefaultSelector), m)})

  def wildPat[_: P]: P[WildPat] = (Index ~  CharIn("_*")).map(i => locate(WildPat(), i))
  def tuplePat[_: P]: P[AsgnPat] = (Index ~ "(" ~/ idPat.rep(sep=",") ~/ ")").map({case (i, ss) =>
    ss.length match {
      case 0 => locate(NoPat(), i)
      case 1 => ss.head
      case _ => locate(TuplePat(ss.toList), i)
    }})

  //Assumptions in assignments are expressed as ?p:(x := f);
  def varPat[_: P]: P[VarPat] = (Index ~ ident).map({case (i, p) => locate(VarPat(p, None), i)})
  /*def varPat[_: P]: P[VarPat] = (Index ~ ident ~ ("{" ~ variable ~ "}").?).
    map({case (i, p, x) => locate(VarPat(p, x), i)})*/
  def idPat[_: P]: P[AsgnPat] = tuplePat | wildPat | varPat
  def fullExPat[_: P]: P[Term] = NoCut(ExpressionParser.patTerm ~ ":" ~ !P("=")).?.map({case None => Nothing case Some(e) => e})
  def exPat[_: P]: P[Term] = NoCut(ExpressionParser.patTerm ~ ":" ~ !P("=")).?.map({case None => Nothing case Some(e) => e}).
    filter({case _: FuncOf => false case _ => true})
  def idExPat[_: P]: P[Option[Ident]] = exPat.map({case (e: BaseVariable) => Some(e) case _ => None })

  private def assembleMod(e: Term, hp: Program): Modify = {
    def getIdent(f: Term): Option[Ident] = f match {case v: Variable => Some(v) case _ => None}
    def traverse(e: Term, hp: Program): List[(Option[Ident], Variable, Option[Term])] = {
      (e, hp) match {
        case (Pair(id, Nothing), Assign(x, f)) => (getIdent(id), x, Some(f)) :: Nil
        case (Pair(id, Nothing), AssignAny(x)) => (getIdent(id), x, None) :: Nil
        case (Pair(xl, xr), Compose(al, ar)) => traverse(xl, al) ++ traverse(xr, ar)
        case (id, Assign(x, f)) => (getIdent(id), x, Some(f)) :: Nil
        case (id, AssignAny(x)) => (getIdent(id), x, None) :: Nil
        case (_, Compose(al, ar)) => traverse(Nothing, al) ++ traverse(Nothing, ar)
      }
    }
    val traversed = traverse(e, hp)
    val (eachId, vars, terms) = StandardLibrary.unzip3(traversed)
    val ids = eachId.filter(_.isDefined).map(_.get)
    val fullIds = (ids, e) match {
      case (Nil, v: Variable) => List(v)
      case (_ :: _, _) => ids
      case _ => Nil
    }
    Modify(fullIds, vars.zip(terms))
  }

  // If someone writes  ?foo(fml), report error  (note exPat silently succeeds because identifier is optional anyway)
  //private def catchMissingColon[_: P](): P[Unit] = (ident.?.flatMap(_ => Fail) | P(""))
  def assume[_: P]: P[Statement] = (Index ~ "?" ~/  exPat.opaque("assumption name or (") ~/ "(" ~/ expression ~/ ")" ~/ ";").map({
    case (i, pat, fml: Formula) => locate(Assume(pat, fml), i)
    case (i, pat, hp: Program) =>  locate(assembleMod(pat, hp), i)
    case (a,b,c) => throw new Exception("Unexpected assumption syntax")
  })

  def assert[_: P]: P[Assert] = (Index ~ "!" ~/ exPat.opaque("assertion name or (")  ~/ "(" ~/ formula ~/ ")" ~/ method.opaque("; or <method>")).map({
    case (i, pat, fml, method) => locate(Assert(pat, fml, method), i)})

  private def zipAssign(lhs: Term, rhs: Option[Term]): List[(Option[Ident], Variable, Option[Term])] = {
    (lhs, rhs) match {
      case (x: Variable, opt) => (None, x, opt) :: Nil
      case (Pair(l,r), None) => zipAssign(l, None) ++ zipAssign(r, None)
      case (Pair(l, r), Some(Pair (lf, rf))) => zipAssign(l, Some(lf)) ++ zipAssign(r, Some(rf))
      case (Pair(l, r), Some(f)) => throw new Exception("lhs and rhs of tuple assignment must have same length")
    }
  }

  def modify[_: P]: P[Statement] = (Index ~ term ~ P(":=") ~/ ("*".!.map(_ => None) | term.map(Some(_))) ~ ";").
    map({case (i, p, opt) =>
      val (idOpts, xs, fs) = StandardLibrary.unzip3(zipAssign(p, opt))
      val ids = idOpts.filter(_.isDefined).map(_.get)
      val statement =
        if (idOpts.isEmpty) {
          KaisarProof.block(xs.zip(fs).map({case (x, f) => Modify(Nil, List((x, f)))}))
        } else Modify(ids, xs.zip(fs))
      locate(statement, i)})

  def labelDefArgs[_: P]: P[List[Variable]] = {
    ("(" ~/ identString.rep(sep = ",") ~/ ")").map(ids => ids.toList.map(Variable(_)))
  }

  def label[_: P]: P[Label] = {
    import NoWhitespace._
    (Index ~ ident ~ labelDefArgs.? ~ ":" ~ !P("=")).map({case (i, id, args) => locate(Label(LabelDef(id.name, args.getOrElse(Nil))), i)})
  }

  def branch[_: P]: P[(Term, Formula, Statement)] = {
    ("case" ~/ (!P("?")).opaque("no ?") ~/ exPat ~/ formula ~/ "=>" ~/ statement.rep).map({case (exp, fml, ss: Seq[Statement]) =>
      (exp, fml, block(ss.toList))})
  }

  def switch[_: P]: P[Switch] = {
    (Index ~ "switch" ~/ CharIn("{(").!).flatMap({
      case (i, "{") => (branch.rep ~/ "}").map(branches => locate(Switch(None, branches.toList), i))
      case (i, "(") => (selector ~/ ")" ~/ "{" ~/ branch.rep ~ "}").
        map({case (sel, branches) => locate(Switch(Some(sel), branches.toList), i)})})
  }


  // track open braces so that when braces are seen, we can distinguish extra vs non-extra brace
  var openBraces: Int = 0
  def parseBlock[_: P]: P[Statement] = ((Index ~ P("{")).map(x => {openBraces = openBraces + 1; x}) ~/ statement.rep(1) ~/ P("}").map(x => {openBraces = openBraces - 1; x}) ~/ P(";").?).map({case (i, ss) => locate(block(ss.toList), i)})
  def boxLoop[_: P]: P[BoxLoop] = (Index ~ statement.rep ~ "*").map({case (i, ss) => locate(BoxLoop(block(ss.toList)), i)})
  def ghost[_: P]: P[Statement] = (Index ~ "/++" ~ statement.rep ~ "++/").map({case (i, ss) => locate(KaisarProof.ghost(block(ss.toList)), i)})
  def inverseGhost[_: P]: P[Statement] = (Index ~ "/--" ~ statement.rep ~ "--/").map({case (i, ss )=> locate(KaisarProof.inverseGhost(block(ss.toList)), i)})
  def parseWhile[_: P]: P[While] = (Index ~ "while" ~/ "(" ~/ formula ~/ ")" ~/ "{" ~ statement.rep ~ "}").
    map({case (i, fml: Formula, ss: Seq[Statement]) => locate(While(Nothing, fml, block(ss.toList)), i)})
  private def increments(f: Term, x: Variable): Boolean = f match {
    case Plus(y, f) if x == y => true
    case Minus(y, f) if x == y => true
    case _ => false
  }
  def parseFor[_: P]: P[For] =
    (Index ~ "for" ~/ "(" ~/ ExpressionParser.assign ~/ ";" ~/ assert.? /* includes ; */ ~/ assume /*includes ;*/ ~/ ExpressionParser.assign ~/ ";".? ~/ ")"
      ~/ "{" ~ statement.rep ~ "}").
    map({case (i, Assign(metX, metF), conv: Option[Assert], guard: Assume, Assign(incL, metIncr), body: Seq[Statement])
      if (incL ==  metX && increments(metIncr, metX)) => locate(
      For(metX, metF, metIncr, conv, guard, block(body.toList)), i)})

  def let[_: P]: P[Statement] = (Index ~ "let" ~
          ( (ident ~ ("(" ~/ ident.rep(sep = ",") ~ ")").?).map(Left(_))
           | term.map(Right(_)))
    ~ ("=" | "<->" | "::=").!).flatMap({
      // @TODO: Give nice errors when term and predicate are missing parens () or when program has parens
     case (i, Left((f, Some(xs))), "=") => term.map(e => locate(LetSym(f, xs.toList, e), i))
     case (i, Right(pat), "=") => term.map(e => locate(Match(pat, e), i))
     case (i, Left((f, Some(xs))), "<->") => formula.map(e => locate(LetSym(f, xs.toList, e), i))
     case (i, Left((f, None)), "::=") => (("{" ~ program ~ "}").map(e => locate(LetSym(f, Nil, e), i)))
  }) ~/ ";"

  def note[_: P]: P[Note] = (Index ~ "note" ~/ ident ~/ "=" ~/ proofTerm ~/ ";").map({case (i, id, pt) => locate(Note(id, pt), i)})

  def atomicODEStatement[_: P]: P[AtomicODEStatement] = (NoCut(fullExPat) ~ atomicOde).map({case (id, ode) => AtomicODEStatement(ode, id)})
  def ghostODE[_: P]: P[DiffStatement] = (Index ~ "/++" ~ diffStatement ~ "++/").
    map({case (i, ds) => locate(DiffGhostStatement(ds), i)})
  def inverseGhostODE[_: P]: P[DiffStatement] = (Index ~ "/--" ~ diffStatement ~ "--/").
    map({case (i, ds) => locate(InverseDiffGhostStatement(ds), i)})
  def terminalODE[_: P]: P[DiffStatement] = ghostODE | inverseGhostODE | atomicODEStatement
  def diffStatement[_: P]: P[DiffStatement] =
    (Index ~ terminalODE.rep(sep = ",", min = 1)).
    map({case (i, dps)=> locate(dps.reduceRight(DiffProductStatement), i)})

  // @TODO: Special error message for missing ?(); maybe
  def domAssume[_: P]: P[DomainStatement] = { (Index ~ "?" ~/  fullExPat ~ "(" ~/ expression ~ ")" ~/ ";").map({
    case (i, pat, fml: Formula) => locate(DomAssume(pat, fml), i)
    case (i, pat, hp: Assign) =>  locate(DomModify(pat, hp.x, hp.e), i)
    case (a,b,c) => throw new Exception("Unexpected assumption syntax")
  })}

  def domAssert[_: P]: P[DomAssert] = (Index ~ "!" ~/ fullExPat ~ "(" ~ formula ~ ")" ~ method.opaque("; or <method>")).map({case (i, id, f, m) => locate(DomAssert(id, f, m), i)})
//  included under "assume" case
//  def domModify[_: P]: P[DomModify] = (Index ~ ExpressionParser.variable ~ ":=" ~ term).map({case (i, id, f) => locate(DomModify(id, f), i)})
  def domWeak[_: P]: P[DomWeak] = (Index ~ "/--" ~/ domainStatement ~ "--/").map({case (i, ds) => locate(DomWeak(ds), i)})
  def terminalDomainStatement[_: P]: P[DomainStatement] = domAssert | domWeak /*|  domModify*/ | domAssume
  def domainStatement[_: P]: P[DomainStatement] = (Index ~ terminalDomainStatement.rep(sep = "&", min = 1)).
    map({case (i, da) => locate(da.reduceRight(DomAnd), i)})

  def proveODE[_: P]: P[ProveODE] =
    (Index ~ diffStatement ~ ("&" ~/ domainStatement).?.
        map({case Some(v) => v case None => DomAssume(Nothing, True)})
      ~ P(";").?
    ).map({case(i, ds, dc) => locate(ProveODE(ds, dc), i)})

  def printGoal[_: P]: P[PrintGoal] = (Index ~ "print" ~/  "(" ~ (literal.map(PrintString) |  expression.map(PrintExpr))  ~ ")" ~ ";").
    map({case (i, pg) => locate(PrintGoal(pg), i)})

  def pragma[_: P]: P[Pragma] = (Index ~ "pragma" ~/ identString ~/ literal ~/ ";").
    filter({case (i, id, str) => Pragmas.canParse(id, str)}).
    map({case (i, id, str) => locate(Pragma(Pragmas.parse(id, str)), i)})

  private def debugParse[_: P](msg: String): P[Unit] = P("").map(_ => println(msg))

  def atomicStatement[_: P]: P[Statement] =
    printGoal | pragma | note | let | switch | assume | assert | ghost | inverseGhost | parseFor | parseWhile  | NoCut(modify).opaque("assignment") | parseBlock | NoCut(label).opaque("label")

  def postfixStatement[_: P]: P[Statement] =
    // line label syntax can collide with ODE label syntax, check labels last.
    // try ODEs before other atoms because ambiguous ghost parse should try ODE first
    ((/*NoCut*/(proveODE) | atomicStatement) ~ Index ~ "*".!.rep).
      map({case (s, i, stars) => locate(stars.foldLeft(s)({case (acc, x) =>
        // NB: rawLastFact will not try to elaborate lastFact because we know we don't have all function definitions yet.
        BoxLoop(acc, Context(acc).rawLastFact.map({case ((x, y)) => (x, y, None)}))
      }), i)})

  def sequence[_: P]: P[Statements] =
    postfixStatement.rep(1).map(ss => ss.toList)

  def boxChoice[_: P]: P[Statement] = {
    (Index ~ sequence.rep(sep = "++", min = 1))
      .map({case (i, ss: Seq[List[Statement]]) => locate(block(ss.reduceRight((l, r) => List(BoxChoice(block(l), block(r))))), i)})
  }

  // error if } detected and no braces open, else do nothing
  private def checkUnmatched[_: P]: P[Unit] = ws ~ (!P("}") | P("").filter(_ => openBraces > 0).opaque(KaisarProgramParser.unmatchedClosingBraceMessage))
  // catch any trailing whitespace too
  def statement[_: P]: P[Statement] = checkUnmatched ~ boxChoice ~ ws.?
  def statements[_: P]: P[List[Statement]] = statement.rep.map(ss => flatten(ss.toList))
  def proof[_: P]: P[Statements] = boxChoice.map(ss => List(ss))
}

object KaisarProgramParser {
  val unmatchedClosingBraceMessage: String =  "braces to be matched but found unmatched closing brace"

  def expression[_: P]: P[Expression] = literal.map(KeYmaeraXParser(_))
  def formula[_: P]: P[Formula] = expression.map(_.asInstanceOf[Formula])
  def program[_: P]: P[Program] = expression.map(_.asInstanceOf[Program])
  def differentialProgram[_: P]: P[DifferentialProgram] = expression.map(_.asInstanceOf[DifferentialProgram])
  def term[_: P]: P[Term] = expression.map(_.asInstanceOf[Term])

  def expectedClass(groups: Msgs, terminals: Msgs): Option[String] = {
    val grps = groups.value.map(_.force)
    val trms = terminals.value.map(_.force)
    if (trms.contains(unmatchedClosingBraceMessage))
      Some(unmatchedClosingBraceMessage)
    else if (trms.contains("\"print\""))
      Some("proof statement")
    else if (trms.contains("\"\\\\forall\""))
      Some("formula")
    else if (trms.contains("\"*\"") && trms.contains("\"-\""))
      None // Some("infix term operator")
    else
      None
  }

  def inferredClass(groups: Msgs, terminals: Msgs): String = {
    expectedClass(groups, terminals).getOrElse("<unknown>")
  }

  def expectation(tr: TracedFailure): String = {
    KaisarProgramParser.expectedClass(tr.groups, tr.terminals).getOrElse(if (tr.failure.label == "") "<unknown>" else if (tr.stack.isEmpty) tr.failure.label else Failure.formatStack(tr.input, tr.stack))
  }

  def recoverErrorMessage(trace: TracedFailure): String = {
    val expected = expectation(trace)
    val location = trace.input.prettyIndex(trace.index)
    val MAX_LENGTH = 80
    val snippet = trace.input.slice(trace.index, trace.index + MAX_LENGTH).filter(c => !Set('\n', '\r').contains(c))
    s"Got: $snippet at $location, \nExpected $expected"
  }

  def prettyIndex(index: Int, input: String): (Int, Int) = {
    val arr = IndexedParserInput(input).prettyIndex(index).split(':')
    (arr(0).toInt, arr(1).toInt)
  }

  def parseSingle(s: String): Statement = {
    val file = s"proof unnamed begin $s end"
    KaisarProgramParser.parseProof(file) match {
      case Decls((td: TheoremDecl) :: Nil) => td.inPf
      case dcl => throw ElaborationException("Expected single proof, got: " + dcl)
    }
  }

  def parseProof(s: String): Decls =
    parse(s, KaisarFileParser.file(_), verboseFailures = true) match {
      case x: Success[Decls] =>
        if (x.index < s.length) {
          val snippet = if (x.index < MAX_CHAR) s.take(x.index) else "..." + s.take(x.index).takeRight(MAX_CHAR)
          val (line, col) = KaisarProgramParser.prettyIndex(x.index, s)
          val msg = s"Could not parse entire input, failed after ($line, $col):\n\t$snippet"
          println(msg)
          println("Displaying nested error message, error location not meaningful.")
          print("Nested ") // prints as "Nested Parse error"
          try {
            val rest = s.drop(x.index)
            parseProof(rest)
            throw KaisarParseException(msg = msg, location = Some(x.index), source = s)
          } catch {
            case e: Throwable => throw KaisarParseException(msg = msg, location = Some(x.index), source = s)
          }
        }
        x.value
      case x: Failure =>
        val exn = KaisarParseException(trace = Some(x.extra.trace(enableLogging = true)), location = Some(x.index), source = s, msg = x.msg)
        println(exn.toString)
        println("\n")
        throw exn
    }
}