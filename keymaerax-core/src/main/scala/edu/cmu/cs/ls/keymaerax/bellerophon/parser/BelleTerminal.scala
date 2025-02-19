package edu.cmu.cs.ls.keymaerax.bellerophon.parser

import edu.cmu.cs.ls.keymaerax.core.{Expression, SubstitutionPair}
import edu.cmu.cs.ls.keymaerax.parser.Parser

import scala.util.matching.Regex

private object PSEUDO  extends BelleTerminal("<pseudo>")

sealed abstract class BelleTerminal(val img: String, val postfix: String = "") {
  assert(img != null)

  override def toString: String = getClass.getSimpleName// + "\"" + img + "\""
  /**
    * @return The regex that identifies this token.
    */
  def regexp: scala.util.matching.Regex = img.r
  val startPattern: Regex = ("^" + regexp.pattern.pattern + postfix).r
}

private case class IDENT(name: String) extends BelleTerminal(name) {
  assert(name != "USMatch" && name.toLowerCase != "partial")
  override def toString = s"IDENT($name)"
}
private object IDENT {
  def regexp: Regex = """([a-zA-Z][a-zA-Z0-9]*)""".r
  //"[\\p{Alpha}\\p{Alnum}]*".r
  val startPattern: Regex = ("^" + regexp.pattern.pattern).r
}


// Combinator Tokens
object SEQ_COMBINATOR extends BelleTerminal(";") {
  override def regexp: Regex = ";".r
}

private object DEPRECATED_SEQ_COMBINATOR extends BelleTerminal("&") {
  override def regexp: Regex = "\\&".r
}

private object EITHER_COMBINATOR extends BelleTerminal("|") {
  override def regexp: Regex = "\\|".r
}

object BRANCH_COMBINATOR extends BelleTerminal("<")

private object ON_ALL extends BelleTerminal("doall")

private object KLEENE_STAR extends BelleTerminal("*") {
  override def regexp: Regex = "\\*".r
}

private object SATURATE extends BelleTerminal("+") {
  override def regexp: Regex = "\\+".r
}

private object OPTIONAL extends BelleTerminal("?") {
  override def regexp: Regex = "\\?".r
}

private case class N_TIMES(n:Int) extends BelleTerminal(s"*$n") {
  assert(n >= 0)
  override def toString = s"NTIMES($n)"
  override def regexp: Regex = s"\\*$n".r
}
private object N_TIMES {
  def regexp: Regex  = """(\*\d+)""".r
  def startPattern: Regex = ("^" + regexp.pattern.pattern).r
}


private object US_MATCH extends BelleTerminal("USMatch")

private object LET extends BelleTerminal("let", "[\\s]")

private object IN extends BelleTerminal("in", "[\\s]")

private object TACTIC extends BelleTerminal("tactic", "[\\s]")

private object AS extends BelleTerminal("as", "[\\s]")

private object EXPAND extends BelleTerminal("expand")

private object EXPANDALLDEFS extends BelleTerminal("expandAllDefs")

private object USING extends BelleTerminal("using")

private object RIGHT_ARROW extends BelleTerminal("=>")

// Separation/Grouping Tokens
private object OPEN_PAREN extends BelleTerminal("(") {
  override def regexp: Regex = "\\(".r
}
private object CLOSE_PAREN extends BelleTerminal(")") {
  override def regexp: Regex = "\\)".r
}
private object COMMA extends BelleTerminal(",")

private trait TACTIC_ARGUMENT

// Positions
private abstract class BASE_POSITION(positionString: String) extends BelleTerminal(positionString) with TACTIC_ARGUMENT
private case class ABSOLUTE_POSITION(positionString: String) extends BASE_POSITION(positionString) {
  override def regexp: Regex = ABSOLUTE_POSITION.regexp
  override val startPattern: Regex = ABSOLUTE_POSITION.startPattern
  override def toString = s"ABSOLUTE_POSITION($positionString)"
}
private object ABSOLUTE_POSITION {
  def regexp: Regex = """(-?\d+(?:\.\d+)*)""".r
  val startPattern: Regex = ("^" + regexp.pattern.pattern).r
}
private case class LAST_SUCCEDENT(positionString: String) extends BASE_POSITION(positionString) {
  override def regexp: Regex = LAST_SUCCEDENT.regexp
  override val startPattern: Regex = LAST_SUCCEDENT.startPattern
  override def toString: String = s"LAST_SUCCEDENT($positionString)"
}
private object LAST_SUCCEDENT {
  def regexp: Regex = """('Rlast(?:\.\d+)*)""".r
  val startPattern: Regex = ("^" + regexp.pattern.pattern).r
}
private case class LAST_ANTECEDENT(positionString: String) extends BASE_POSITION(positionString) {
  override def regexp: Regex = LAST_ANTECEDENT.regexp
  override val startPattern: Regex = LAST_ANTECEDENT.startPattern
  override def toString: String = s"LAST_ANTECEDENT($positionString)"
}
private object LAST_ANTECEDENT {
  def regexp: Regex = """('Llast(?:\.\d+)*)""".r
  val startPattern: Regex = ("^" + regexp.pattern.pattern).r
}
private object SEARCH_SUCCEDENT extends BelleTerminal("'R") with TACTIC_ARGUMENT
private object SEARCH_ANTECEDENT extends BelleTerminal("'L") with TACTIC_ARGUMENT
private object SEARCH_EVERYWHERE extends BelleTerminal("'_") with TACTIC_ARGUMENT {
  override def regexp: Regex = "'\\_".r
}
private object EXACT_MATCH extends BelleTerminal("==") with TACTIC_ARGUMENT
private object UNIFIABLE_MATCH extends BelleTerminal("~=") with TACTIC_ARGUMENT

private object PARTIAL extends BelleTerminal("partial") {
  override def regexp: Regex = "(?i)partial".r // allow case-insensitive use of the word partial.
}

/** A tactic argument expression. We allow strings, terms, and formulas as arguments. */
private case class EXPRESSION(exprString: String, delimiters: (String, String)) extends BelleTerminal(exprString) with TACTIC_ARGUMENT {
  lazy val undelimitedExprString: String = exprString.stripPrefix(delimiters._1).stripSuffix(delimiters._2)

  /** Parses the `exprString` as dL expression. May throw a parse exception. */
  lazy val expression: Either[Expression, SubstitutionPair] = {
    assert(exprString.startsWith(delimiters._1) && exprString.endsWith(delimiters._2),
      s"EXPRESSION.regexp should ensure delimited expression begin }and end with $delimiters, but an EXPRESSION was constructed with argument: $exprString")

    //Remove delimiters and parse the expression.
    val exprs = undelimitedExprString.split("~>")
    assert(1 <= exprs.size && exprs.size <= 2, "Expected either single expression or substitution pair of expressions, but got " + undelimitedExprString)
    if (exprs.size == 1) Left(Parser.parser(exprs.head))
    else {
      import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
      Right(undelimitedExprString.asSubstitutionPair)
    }
  }

  override def regexp: Regex = EXPRESSION.regexp
  override val startPattern: Regex = EXPRESSION.startPattern

  override def toString = s"EXPRESSION($exprString)"

  override def equals(other: Any): Boolean = other match {
    case EXPRESSION(str, _) => str == exprString
    case _ => false
  }
}
private object EXPRESSION {
  def regexp: Regex = """(\{\`[\s\S]*?\`\})|(\"[^\"]*\")""".r
  val startPattern: Regex = ("^" + regexp.pattern.pattern).r
}

object EOF extends BelleTerminal("<EOF>") {
  override def regexp: Regex = "$^".r //none.
}
