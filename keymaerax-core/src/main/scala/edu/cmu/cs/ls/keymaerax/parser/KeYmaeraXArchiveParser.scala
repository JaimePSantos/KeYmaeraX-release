/**
  * Copyright (c) Carnegie Mellon University.
  * See LICENSE.txt for the conditions of this license.
  */

package edu.cmu.cs.ls.keymaerax.parser

import edu.cmu.cs.ls.keymaerax.bellerophon.{BelleExpr, DefTactic}
import edu.cmu.cs.ls.keymaerax.bellerophon.parser.{BelleLexer, BelleParser}
import edu.cmu.cs.ls.keymaerax.bellerophon.parser.BelleParser.{BelleToken, DefScope}

/**
  * Splits a KeYmaera X archive into its parts and forwards to respective problem/tactic parsers. An archive contains
  * at least one entry combining a model in the `.kyx`` format and possibly a (partial) proof tactic.
  *
  * Format example:
  * {{{
  *   ArchiveEntry "Entry 1".
  *     Description "optional description text".
  *     ProgramVariables ... End.
  *     Definitions ... End.
  *     Problem ... End.
  *     Tactic "Proof 1" ... End.
  *     Tactic "Proof 2" ... End.
  *   End.
  *   ArchiveEntry "Entry 2". ... End.
  * }}}
  *
  * @author Stefan Mitsch
  * @see [[https://github.com/LS-Lab/KeYmaeraX-release/wiki/KeYmaera-X-Syntax-and-Informal-Semantics Wiki]]
  * @see [[DLArchiveParser]]
  */
object KeYmaeraXArchiveParser extends KeYmaeraXArchiveParserBase {

  /** Parses an archive from the source at path `file`. Use file#entry to refer to a specific entry in the file. */
  override def parseFromFile(file: String): List[ParsedArchiveEntry] = {
    file.split('#').toList match {
      case fileName :: Nil =>
        val input = scala.io.Source.fromFile(fileName, "ISO-8859-1").mkString
        parse(input)
      case fileName :: entryName :: Nil =>
        val input
        = scala.io.Source.fromFile(fileName, "ISO-8859-1").mkString
        getEntry(entryName, input).
          getOrElse(throw new IllegalArgumentException("Unknown archive entry " + entryName)) :: Nil
    }
  }

  override protected def convert(t: Tactic, defs: Declaration): (String, String, BelleExpr) = try {
    val tokens = BelleLexer(t.tacticText).map(tok => BelleToken(tok.terminal, shiftLoc(tok.location, t.belleExprLoc)))

    // backwards compatibility: expandAll if model has expansible definitions and tactic does not expand any, and expand all tactic arguments
    val usMatchers = defs.decls.filter(_._2._3.isDefined).map({ case ((name, idx), _) => name + idx.map("_" + _).getOrElse("") })
    val expandAll = usMatchers.nonEmpty &&
      !BelleParser.tacticExpandsDefsExplicitly(t.tacticText) &&
      usMatchers.mkString("US\\(\"(", "|", ")").r.findFirstIn(t.tacticText).isEmpty
    val tactic = BelleParser.parseTokenStream(tokens, DefScope[String, DefTactic](), None, defs, expandAll)

    (t.name, t.tacticText, tactic)
  } catch {
    case ex: ParseException if ex.msg.startsWith("Lexer") =>
      val shiftedLoc = shiftLoc(ex.loc, t.belleExprLoc)
      val msg = (ex.msg.substring(0, ex.msg.indexOf("in `")) +
        ex.msg.substring(ex.msg.indexOf("beginning with character"))).
        replaceAllLiterally(ex.loc.line + ":" + ex.loc.column, shiftedLoc.line + ":" + shiftedLoc.column)
      throw ParseException(msg, shiftedLoc, ex.found, ex.expect, ex.after, ex.state, ex.cause, ex.hint)
  }

  private def shiftLoc(loc: Location, offset: Location): Location = {
    if (loc.line <= 1) loc match {
      case Region(l, c, el, ec) if el == l =>
        Region(l + offset.line - 1, c + offset.column - 1, el + offset.line -1, ec + offset.column - 1)
      case Region(l, c, el, ec) if el > l =>
        Region(l + offset.line - 1, c + offset.column - 1, el + offset.line -1, ec)
      case SuffixRegion(l, c) => SuffixRegion(l + offset.line - 1, c + offset.column - 1)
      case l => l.addLines(offset.line - 1) //
    } else {
      loc.addLines(offset.line - 1)
    }
  }

}
