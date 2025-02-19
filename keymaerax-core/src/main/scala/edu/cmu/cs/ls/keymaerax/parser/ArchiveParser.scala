package edu.cmu.cs.ls.keymaerax.parser

import java.io.InputStream

import edu.cmu.cs.ls.keymaerax.bellerophon.BelleExpr
import edu.cmu.cs.ls.keymaerax.core.{BaseVariable, Bool, DifferentialSymbol, DotTerm, Exists, Expression, Forall, Formula, FuncOf, Function, NamedSymbol, Nothing, PredOf, Program, ProgramConst, Real, Sort, StaticSemantics, SubstitutionClashException, SubstitutionPair, SystemConst, Term, Trafo, Tuple, USubst, Unit, UnitFunctional, UnitPredicational, Variable}
import edu.cmu.cs.ls.keymaerax.infrastruct.ExpressionTraversal.{ExpressionTraversalFunction, StopTraversal}
import edu.cmu.cs.ls.keymaerax.infrastruct.{DependencyAnalysis, ExpressionTraversal, FormulaTools, PosInExpr}
import edu.cmu.cs.ls.keymaerax.parser.ArchiveParser.{Name, Signature}
import edu.cmu.cs.ls.keymaerax.infrastruct.Augmentors._

import scala.collection.immutable.List

/** A parsed declaration, which assigns a signature to names. */
case class Declaration(decls: Map[Name, Signature]) {
  /** The declarations as topologically sorted substitution pairs. */
  lazy val substs: List[SubstitutionPair] = topSort(decls.filter(_._2._4.isDefined).map({
    case (name, (domain, sort, argNames, interpretation, loc)) =>
      // elaborate to functions for topSort (topSort uses signature)
      (name, (domain, sort, argNames, interpretation.map(elaborateToFunctions(_)), loc))
  })).map((declAsSubstitutionPair _).tupled)

  /** Declared names and signatures as [[NamedSymbol]]. */
  lazy val asNamedSymbols: List[NamedSymbol] = decls.map({ case ((name, idx), (domain, sort, _, rhs, _)) => sort match {
    case Real | Bool if domain.isEmpty => Variable(name, idx, sort)
    case Real | Bool if domain.isDefined => Function(name, idx, domain.get, sort)
    case Trafo =>
      assert(idx.isEmpty, "Program/system constants are not allowed to have an index, but got " + name + "_" + idx)
      rhs match {
        case Some(p: Program) if FormulaTools.dualFree(p) => SystemConst(name)
        case _ => ProgramConst(name)
      }
  }}).toList

  /** Topologically sorts the names in `decls`. */
  private def topSort(decls: Map[Name, Signature]): List[(Name, Signature)] = {
    val sortedNames = DependencyAnalysis.dfs[Name](decls.map({ case (name, (_, _, _, repl, _)) =>
      name -> repl.map(StaticSemantics.signature).map(_.map(ns => (ns.name, ns.index))).getOrElse(Set.empty) }))
    decls.toList.sortBy(s => sortedNames.indexOf(s._1))
  }

  /** Joins two declarations. */
  def ++(other: Declaration): Declaration = Declaration(decls ++ other.decls)

  /** Finds the definition with `name` and index `idx`. */
  def find(name: String, idx: Option[Int] = None): Option[Signature] = decls.get(name -> idx)

  /** Applies substitutions per `substs` exhaustively to expression-like `arg`. */
  def exhaustiveSubst[T <: Expression](arg: T): T = try {
    elaborateToFunctions(arg.exhaustiveSubst(USubst(substs))).asInstanceOf[T]
  } catch {
    case ex: SubstitutionClashException =>
      throw ParseException("Definition " + ex.context + " as " + ex.e + " must declare arguments " + ex.clashes, ex)
  }

  /** Expands all symbols in expression `arg` fully. */
  def expandFull[T <: Expression](arg: T): T = try {
    exhaustiveSubst(elaborateToFunctions(elaborateToSystemConsts(arg)))
  } catch {
    case ex: SubstitutionClashException =>
      throw ParseException("Definition " + ex.context + " as " + ex.e + " must declare arguments " + ex.clashes, ex)
  }

  /** Elaborates variable uses of declared functions, except those listed in taboo. */
  //@todo need to look into concrete programs that implement program constants when elaborating
  def elaborateToFunctions[T <: Expression](expr: T, taboo: Set[Function] = Set.empty): T = expr.elaborateToFunctions(asNamedSymbols.toSet -- taboo).asInstanceOf[T]

  /** Elaborates program constants to system constants if their definition is dual-free. */
  def elaborateToSystemConsts[T <: Expression](expr: T): T = {
    val elaborator = new ExpressionTraversalFunction() {
      override def preP(p: PosInExpr, e: Program): Either[Option[StopTraversal], Program] = e match {
        case ProgramConst(name, space) =>
          decls.find(_._1._1 == name).flatMap(_._2._4) match {
            case Some(prg: Program) =>
              if (FormulaTools.dualFree(prg)) Right(SystemConst(name, space))
              else Left(None)
            case Some(_) => Left(None) // symbol is not defined as a program (typeAnalysis error will be raised later)
            case None => Left(None) // undefined symbol (could be program or game)
          }
        case _ => Left(None)
      }
    }

    expr match {
      case f: Formula => ExpressionTraversal.traverse(elaborator, f).get.asInstanceOf[T]
      case t: Term => ExpressionTraversal.traverse(elaborator, t).get.asInstanceOf[T]
      case p: Program => ExpressionTraversal.traverse(elaborator, p).get.asInstanceOf[T]
    }
  }

  /** Elaborates all declarations to dots. */
  def elaborateWithDots: Declaration = Declaration(decls.map(d => elaborateWithDots(d._1, d._2)))

  /** Elaborates the interpretation in `signature` to dots. */
  private def elaborateWithDots(name: Name, signature: Signature): (Name, Signature) = signature._4 match {
    case None => (name, signature)
    case Some(interpretation) => signature._3 match {
      case None => (name, signature)
      case Some(argNames) =>
        val arg = signature._1 match {
          case Some(Unit) => Nothing
          case Some(s: Tuple) => s.toDots(0)._1
          case Some(s) => DotTerm(s)
          case None => Nothing
        }

        // backwards compatible dots
        val dotTerms =
          if (argNames.size == 1) argNames.map(v => v -> DotTerm(v._2, None))
          else argNames.zipWithIndex.map({ case (v, i) => v -> DotTerm(v._2, Some(i)) })
        val dottedInterpretation = dotTerms.foldRight(interpretation)({ case ((((name, index), sort), dot), dotted) =>
          // signature may contain DotTerms because of anonymous arguments
          if (name != DotTerm().name) dotted.replaceFree(Variable(name, index, sort), dot)
          else dotted
        })

        val undeclaredDots = dotsOf(dottedInterpretation) -- dotsOf(arg)
        if (undeclaredDots.nonEmpty) throw ParseException(
          "Function/predicate " + name._1 + name._2.map("_" + _).getOrElse("") + "(" +
            argNames.map(an => (if (an._1._1 != DotTerm().name) an._1._1 else "•") + an._1._2.map("_" + _).getOrElse("")).mkString(",") + ")" +
            " defined using undeclared " + undeclaredDots.map(_.prettyString).mkString(","),
          UnknownLocation)
        (name, (signature._1, signature._2, signature._3, Some(dottedInterpretation), signature._5))
    }
  }

  /** Returns the dots used in expression `e`. */
  private def dotsOf(e: Expression): Set[DotTerm] = {
    val dots = scala.collection.mutable.Set[DotTerm]()
    val traverseFn = new ExpressionTraversalFunction() {
      override def preT(p: PosInExpr, t: Term): Either[Option[StopTraversal], Term] = t match {
        case d: DotTerm => dots += d; Left(None)
        case _ => Left(None)
      }
    }
    e match {
      case t: Term => ExpressionTraversal.traverse(traverseFn, t)
      case f: Formula => ExpressionTraversal.traverse(traverseFn, f)
      case p: Program => ExpressionTraversal.traverse(traverseFn, p)
    }
    dots.toSet
  }

  /** Turns a function declaration (with defined body) into a substitution pair. */
  private def declAsSubstitutionPair(name: Name, signature: Signature): SubstitutionPair = {
    require(signature._4.isDefined, "Substitution only for defined functions")
    val (_, (domain, sort, _, Some(interpretation), loc)) = elaborateWithDots(name, signature)

    val (arg, sig) = domain match {
      case Some(Unit) => (Nothing, Unit)
      case Some(s: Tuple) => (s.toDots(0)._1, s)
      case Some(s) => (DotTerm(s), s)
      case None => (Nothing, Unit)
    }
    val what = sort match {
      case Real => FuncOf(Function(name._1, name._2, sig, signature._2), arg)
      case Bool => PredOf(Function(name._1, name._2, sig, signature._2), arg)
      case Trafo =>
        assert(name._2.isEmpty, "Expected no index in program const name, but got " + name._2)
        assert(signature._1.getOrElse(Unit) == Unit, "Expected domain Unit in program const signature, but got " + signature._1)
        interpretation match {
          case prg: Program =>
            if (FormulaTools.dualFree(prg)) SystemConst(name._1)
            else ProgramConst(name._1)
          case e => throw ParseException("Definition of " + name._1 + " is not a program, but a " + e.kind, loc)
        }
    }
    val repl = elaborateToFunctions(interpretation) match {
      case r@FuncOf(fn: Function, c) =>
        if (what.sort == fn.sort) r
        else PredOf(Function(fn.name, fn.index, fn.domain, what.sort), c)
      case p@PredOf(fn: Function, c) =>
        if (what.sort == fn.sort) p
        else FuncOf(Function(fn.name, fn.index, fn.domain, what.sort), c)
      case r => r
    }

    val undeclaredDots = dotsOf(repl) -- dotsOf(arg)
    if (undeclaredDots.nonEmpty) throw ParseException(
      "Function/predicate " + what.prettyString + " defined using undeclared " + undeclaredDots.map(_.prettyString).mkString(","),
      UnknownLocation)

    SubstitutionPair(what, repl)
  }
}

/** The entry name, kyx file content (model), definitions, parsed model, and parsed named tactics. */
case class ParsedArchiveEntry(name: String, kind: String, fileContent: String, problemContent: String,
                              defs: Declaration,
                              model: Expression,
                              tactics: List[(String, String, BelleExpr)],
                              annotations: List[(Expression, Expression)],
                              info: Map[String, String]) {
  /** True if this entry is an exercise, false otherwise. */
  def isExercise: Boolean = kind=="exercise"
  /** The model with all definitions expanded. */
  def expandedModel: Expression = defs.exhaustiveSubst(model)
  /** Return an archive with modified problem contents, otherwise identical./ */
  def withProblemContent(newProblemContent: String): ParsedArchiveEntry =
    ParsedArchiveEntry(name, kind, fileContent, newProblemContent, defs, model, tactics, annotations, info)
  /** Return an archive with modified file contents, otherwise identical./ */
  def withFileContent(newFileContent: String): ParsedArchiveEntry =
    ParsedArchiveEntry(name, kind, newFileContent, problemContent, defs, model, tactics, annotations, info)
}

trait ArchiveParser extends (String => List[ParsedArchiveEntry]) {
  def parse(input: String, parseTactics: Boolean = true): List[ParsedArchiveEntry]
  def parseFromFile(file: String): List[ParsedArchiveEntry]

  /** Returns the first entry in `input` as formula. */
  def parseAsFormula(input: String): Formula = parse(input, parseTactics=false).head.model.asInstanceOf[Formula]

  /** Returns the first entry in `input` as a formula with all definitions fully expanded. */
  def parseAsExpandedFormula(input: String): Formula = parse(input, parseTactics=false).head match {
    case ParsedArchiveEntry(_, _, _, _, defs, model, _, _, _) => defs.expandFull(model.asInstanceOf[Formula])
  }

  /** Returns the first entry in `in` as formula. */
  def parseAsFormula(in: InputStream): Formula = parseAsFormula(io.Source.fromInputStream(in).mkString)

  /** Reads a specific entry from the archive. */
  def getEntry(name: String, content: String): Option[ParsedArchiveEntry] = parse(content).find(_.name == name)

  /** Parses a single entry. */
  def parseProblem(input: String, parseTactics: Boolean = true): ParsedArchiveEntry = {
    val entries = parse(input, parseTactics)
    if (entries.size == 1) entries.head
    else throw ParseException("Expected a single entry, but got " + entries.size, UnknownLocation)
  }

  /** Indicates whether or not the model represents an exercise. */
  def isExercise(model: String): Boolean = model.contains("__________")
}

object ArchiveParser extends ArchiveParser {
  /** Name is alphanumeric name and index. */
  type Name = (String, Option[Int])
  /** Signature is a domain sort, codomain sort, argument names, expression used as "interpretation", location that starts the declaration. */
  type Signature = (Option[Sort], Sort, Option[List[(Name, Sort)]], Option[Expression], Location)
  /** Input signature as defined in the input file ([[Signature]] is extracted from it). */
  type InputSignature = (List[NamedSymbol], Option[Expression])

  /* @note mutable state for switching out the default parser. */
  private[this] var p: ArchiveParser = _

  /** The parser that is presently used per default. */
  def parser: ArchiveParser = p

  /** Set a new parser. */
  def setParser(parser: ArchiveParser): Unit = { p = parser }

  /** Parses `input`. */
  override def apply(input: String): List[ParsedArchiveEntry] = parser(input)

  override def parse(input: String, parseTactics: Boolean): List[ParsedArchiveEntry] = parser.parse(input, parseTactics)

  override def parseFromFile(file: String): List[ParsedArchiveEntry] = parser.parseFromFile(file)

  private[parser] object BuiltinDefinitions {
    val defs: Declaration =
      Declaration(Map(("abs", None) -> (Some(Real), Real, Some(List((("\\cdot", None), Real))), None, UnknownLocation))) ++
      Declaration(Map(("min", None) -> (Some(Tuple(Real, Real)), Real, Some(List((("\\cdot", Some(0)), Real), (("\\cdot", Some(1)), Real))), None, UnknownLocation))) ++
      Declaration(Map(("max", None) -> (Some(Tuple(Real, Real)), Real, Some(List((("\\cdot", Some(0)), Real), (("\\cdot", Some(1)), Real))), None, UnknownLocation)))
  }

  private[parser] object BuiltinAnnotationDefinitions {
    val defs: Declaration =
      Declaration(Map(("old", None) -> (Some(Real), Real, Some(List((("\\cdot", None), Real))), None, UnknownLocation)))
  }


  /** Elaborates variable uses of nullary function symbols in `entry` and its definitions/annotations, performs
    * DotTerm abstraction in entry definitions, and semantic/type analysis of the results. */
  def elaborate(entry: ParsedArchiveEntry): ParsedArchiveEntry = {
    val elaboratedDefs = elaborateDefs(entry.defs)

    // elaborate model and check
    val elaboratedModel = try {
      elaboratedDefs.elaborateToSystemConsts(elaboratedDefs.elaborateToFunctions(entry.model).asInstanceOf[Formula])
    } catch {
      case ex: AssertionError => throw ParseException(ex.getMessage, ex)
    }
    val fullyExpandedModel = try {
      elaboratedDefs.exhaustiveSubst(elaboratedModel)
    } catch {
      case ex: AssertionError => throw ParseException(ex.getMessage, ex)
    }
    KeYmaeraXParser.semanticAnalysis(fullyExpandedModel) match {
      case None =>
      case Some(error) => throw ParseException("Semantic analysis error\n" + error, fullyExpandedModel)
    }
    //@note bare formula input without any definitions uses default meaning of symbols
    if (elaboratedDefs.decls.nonEmpty) typeAnalysis(entry.name, entry.defs ++ BuiltinDefinitions.defs, elaboratedModel) //throws ParseExceptions.
    checkUseDefMatch(elaboratedModel, elaboratedDefs)

    // analyze and report annotations
    val elaboratedAnnotations = elaborateAnnotations(entry.annotations, elaboratedDefs)
    val expandedAnnotations = elaborateAnnotations(expandAnnotations(entry.annotations, elaboratedDefs), elaboratedDefs)
    (elaboratedAnnotations ++ expandedAnnotations).distinct.foreach({
      case (e: Program, a: Formula) =>
        if (elaboratedDefs.decls.nonEmpty) typeAnalysis(entry.name, elaboratedDefs ++ BuiltinDefinitions.defs ++ BuiltinAnnotationDefinitions.defs, a)
        else typeAnalysis(entry.name, declarationsOf(entry.model) ++ BuiltinDefinitions.defs ++ BuiltinAnnotationDefinitions.defs, a)
        KeYmaeraXParser.annotationListener(e, a)
    })

    entry.copy(
      model = elaboratedModel,
      defs = elaboratedDefs.elaborateWithDots,
    )
  }

  /** Checks that uses in `problem` match the declarations.
    * @throws ParseException on use-def mismatch.
    */
  private def checkUseDefMatch(problem: Formula, defs: Declaration): Unit = {
    // check that definitions and use match
    val symbols = StaticSemantics.symbols(problem) ++ defs.substs.flatMap(s => StaticSemantics.symbols(s.repl))
    val defSymbols = defs.substs.map(_.what)
    val mismatches = defSymbols.map({
      case n: NamedSymbol => symbols.find(u => u.name == n.name && u.index == n.index && u.kind != n.kind).map(n -> _)
      case _ => None
    }).filter(_.isDefined).map(_.get)
    if (mismatches.nonEmpty) {
      val mismatchDescription = mismatches.map({ case (defSym, sym) =>
        "Symbol '" + defSym.prettyString + "' defined as " + defSym.kind +
          ", but used as " + sym.kind + " in " + sym.prettyString
      }).mkString("\n")
      val found = mismatches.map({ case (_, sym) => sym.prettyString }).mkString(", ")
      val expected = mismatches.map({ case (defSym, _) => defSym.prettyString }).mkString(", ")
      throw new ParseException("All definitions and uses must match, but found the following mismatches:\n" +
        mismatchDescription, UnknownLocation, found, expected, "", "")
    }
  }


  /** Elaborates to functions in annotations.
    * @param annotations the annotations to elaborate
    * @param defs lists functions to elaborate to
    * @throws ParseException if annotations are not formulas, not attached to programs, or type analysis of annotations fails
    * */
  private def elaborateAnnotations(annotations: List[(Expression, Expression)], defs: Declaration): List[(Expression, Expression)] = {
    annotations.map({
      case (e: Program, a: Formula) =>
        val substPrg = defs.elaborateToSystemConsts(defs.elaborateToFunctions(e))
        val substFml = defs.elaborateToSystemConsts(defs.elaborateToFunctions(a))
        (substPrg, substFml)
      case (_: Program, a) => throw ParseException("Annotation must be formula, but got " + a.prettyString, UnknownLocation)
      case (e, _) => throw ParseException("Annotation on programs only, but was on " + e.prettyString, UnknownLocation)
    })
  }


  /** Expands definitions in annotations to create fully expanded annotations. */
  private def expandAnnotations(annotations: List[(Expression, Expression)], defs: Declaration): List[(Expression, Expression)] = {
    annotations.map({
      case (e: Program, a: Formula) =>
        val substPrg = defs.exhaustiveSubst(defs.elaborateToFunctions(e))
        val substFml = defs.exhaustiveSubst(defs.elaborateToFunctions(a))
        (substPrg, substFml)
      case (_: Program, a) => throw ParseException("Annotation must be formula, but got " + a.prettyString, UnknownLocation)
      case (e, _) => throw ParseException("Annotation on programs only, but was on " + e.prettyString, UnknownLocation)
    })
  }

  /** Extracts declarations per static semantics of the expression `parsedContent`. */
  private[parser] def declarationsOf(parsedContent: Expression): Declaration = {
    val symbols = StaticSemantics.symbols(parsedContent)
    val fnDecls = symbols.filter(_.isInstanceOf[Function]).map(_.asInstanceOf[Function]).map(fn =>
      (fn.name, fn.index) -> (Some(fn.domain), fn.sort, None, None, UnknownLocation)
    ).toMap[(String, Option[Int]),(Option[Sort], Sort, Option[List[(Name, Sort)]], Option[Expression], Location)]
    val varDecls = symbols.filter(_.isInstanceOf[BaseVariable]).map(v =>
      (v.name, v.index) -> (None, v.sort, None, None, UnknownLocation)
    ).toMap[(String, Option[Int]),(Option[Sort], Sort, Option[List[(Name, Sort)]], Option[Expression], Location)]
    Declaration(fnDecls ++ varDecls)
  }

  /** Elaborates variables to function symbols and program constants to system constants in definitions `defs`. */
  def elaborateDefs(defs: Declaration): Declaration = {
    def taboos(signature: List[((String, Option[Int]), Sort)]): Set[Function] = {
      signature.filter({ case ((name, _), _) => name != "\\cdot" }).map({ case((name, idx), sort) => Function(name, idx, Unit, sort) }).toSet
    }

    defs.copy(decls = defs.decls.map({ case ((name, index), (domain, sort, argNames, interpretation, loc)) =>
      ((name, index), (domain, sort, argNames, interpretation.map(i =>
        defs.elaborateToSystemConsts(defs.elaborateToFunctions(i, taboos(argNames.getOrElse(Nil))))), loc))
    }))
  }

  /**
    * Type analysis of expression according to the given type declarations decls
    * @param name the entry name (for error messages)
    * @param d the type declarations known from the context (e.g., as parsed from the Definitions and ProgramVariables block of an entry)
    * @param expr the expression to analyze
    * @throws [[edu.cmu.cs.ls.keymaerax.parser.ParseException]] if the type analysis fails.
    */
  def typeAnalysis(name: String, d: Declaration, expr: Expression): Boolean = {
    StaticSemantics.symbols(expr).forall({
      case f: Function =>
        val (declaredDomain, declaredSort, _, _, loc: Location) = d.decls.get((f.name,f.index)) match {
          case Some(decl) => decl
          case None => throw ParseException.typeError(name + ": undefined function symbol", f, f.sort + "", UnknownLocation,
            "Make sure to declare all variables in ProgramVariable and all symbols in Definitions block.")
        }
        if (f.sort != declaredSort) throw ParseException.typeDeclError(s"$name: ${f.prettyString} declared with sort $declaredSort but used where sort ${f.sort} was expected.", declaredSort + " function", f.sort + "", loc)
        else if (!declaredDomain.contains(f.domain)) {
          (f.domain, declaredDomain) match {
            case (_, Some(r)) => throw ParseException.typeDeclError(s"$name: ${f.prettyString} declared with domain $r but used where domain ${f.domain} was expected.", r + "", f.domain + "", loc)
            case (_, None) => throw ParseException.typeDeclError(s"$name: ${f.prettyString} declared as a variable of sort ${f.sort} but used as a function with arguments.", "no arguments", "function with arguments", loc)
            //The other cases can't happen -- we know f is a function so we know it has a domain.
          }
        }
        else true
      case DifferentialSymbol(v) => d.decls.contains(v.name, v.index) //@note hence it is checked as variable already
      case x: Variable =>
        if (quantifiedVars(expr).contains(x)) true //Allow all undeclared variables if they are at some point bound by a \forall or \exists. @todo this is an approximation. Should only allow quantifier bindings...
        else {
          val (declaredSort, declLoc) = d.decls.get((x.name, x.index)) match {
            case Some((None, sort, _, _, loc)) => (sort, loc)
            case Some((Some(domain), sort, _, _, loc)) =>
              throw ParseException.typeDeclError(s"$name: ${x.name} was declared as a function but must be a variable when it is assigned to or has a differential equation.", domain + "->" + sort + " Function", "Variable of sort Real", loc)
            case None => throw ParseException.typeDeclGuessError(name + ": undefined symbol " + x + " with index " + x.index, "undefined symbol", x, UnknownLocation,
              "Make sure to declare all variables in ProgramVariable and all symbols in Definitions block.")
          }
          if (x.sort != declaredSort) throw ParseException.typeDeclGuessError(s"$name: ${x.prettyString} declared with sort $declaredSort but used where a ${x.sort} was expected.", declaredSort + "", x, declLoc)
          x.sort == declaredSort
        }
      case _: UnitPredicational => true //@note needs not be declared
      case _: UnitFunctional => true //@note needs not be declared
      case _: DotTerm => true //@note needs not be declared
      case _ => false
    })
  }

  /** Returns all the quantified variables in an expression. Used in [[typeAnalysis()]] */
  private def quantifiedVars(expr : Expression) = {
    val quantifiedVariables : scala.collection.mutable.Set[NamedSymbol] = scala.collection.mutable.Set()

    ExpressionTraversal.traverse(new ExpressionTraversalFunction {
      override def preF(p: PosInExpr, e: Formula): Either[Option[ExpressionTraversal.StopTraversal], Formula] = {
        //Add all quantified variables to the quantifiedVariables set.
        e match {
          case Forall(xs, _) => xs.foreach(v => quantifiedVariables += v)
          case Exists(xs, _) => xs.foreach(v => quantifiedVariables += v)
          case _ =>
        }
        Left(None)
      }
    }, expr.asInstanceOf[Formula])

    quantifiedVariables
  }
}