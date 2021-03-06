/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.silicon.decider

import scala.collection.mutable
import viper.silver.components.StatefulComponent
import viper.silicon.interfaces.decider.TermConverter
import viper.silicon.reporting.Bookkeeper
import viper.silicon.state.Identifier
import viper.silicon.state.terms._
import viper.silicon.supporters.qps.{SummarisingFvfDefinition, SummarisingPsfDefinition }
import viper.silver.ast.pretty.FastPrettyPrinterBase

class TermToSMTLib2Converter(bookkeeper: Bookkeeper)
    extends FastPrettyPrinterBase
       with TermConverter[String, String, String]
       with StatefulComponent {

  override val defaultIndent = 2
  override val defaultWidth = 80

  lazy val uninitialized: Cont = value("<not initialized>")

  private var sanitizedNamesCache: mutable.Map[String, String] = _

  private val nameSanitizer = new SmtlibNameSanitizer

  def convert(s: Sort): String = {
    super.pretty(defaultWidth, render(s))
  }

  protected def render(sort: Sort): Cont = sort match {
    case sorts.Int => "Int"
    case sorts.Bool => "Bool"
    case sorts.Perm => "$Perm"
    case sorts.Snap => "$Snap"
    case sorts.Ref => "$Ref"
    case sorts.Seq(elementSort) => text("$Seq<") <> render(elementSort) <> ">"
    case sorts.Set(elementSort) => text("$Set<") <> render(elementSort) <> ">"
    case sorts.Multiset(elementSort) => text("$Multiset<") <> render(elementSort) <> ">"
    case sorts.UserSort(id) => sanitize(id)

    case sorts.Unit =>
      /* Sort Unit corresponds to Scala's Unit type and is used, e.g., as the
       * domain sort of nullary functions.
       */
      ""

    case sorts.FieldValueFunction(codomainSort) => text("$FVF<") <> render(codomainSort) <> ">"
    case sorts.PredicateSnapFunction(codomainSort) => text("$PSF<") <> render(codomainSort) <> ">"
  }

  def convert(d: Decl): String = {
    super.pretty(defaultWidth, render(d))
  }

  protected def render(decl: Decl): Cont = decl match {
    case SortDecl(sort: Sort) =>
      parens(text("declare-sort") <+> render(sort))

    case FunctionDecl(fun: Function) =>
      val idDoc = sanitize(fun.id)
      val argSortsDoc = fun.argSorts.map(render)
      val resultSortDoc = render(fun.resultSort)

      if (argSortsDoc.isEmpty)
        parens(text("declare-const") <+> idDoc <+> resultSortDoc)
      else
        parens(text("declare-fun") <+> idDoc <+> parens(ssep(argSortsDoc.to[collection.immutable.Seq], space)) <+> resultSortDoc)

    case SortWrapperDecl(from, to) =>
      val id = Identifier(sortWrapperName(from, to))
      val fct = FunctionDecl(Fun(id, from, to))

      render(fct)

    case MacroDecl(id, args, body) =>
      val idDoc = sanitize(id)
      val argDocs = (args map (v => parens(text(sanitize(v.id)) <+> render(v.sort)))).to[collection.immutable.Seq]
      val bodySortDoc = render(body.sort)
      val bodyDoc = render(body)

      parens(text("define-fun") <+> idDoc <+> parens(ssep(argDocs, space)) <+> bodySortDoc <> nest(defaultIndent, line <> bodyDoc))
  }

  def convert(t: Term): String = {
    super.pretty(defaultWidth, render(t))
  }

  protected def render(term: Term): Cont = term match {
    case lit: Literal => render(lit)

    case Ite(t0, t1, t2) =>
      renderNAryOp("ite", t0, t1, t2)

    case fapp: Application[_] =>
      if (fapp.args.isEmpty)
        sanitize(fapp.applicable.id)
      else
        parens(text(sanitize(fapp.applicable.id)) <+> ssep((fapp.args map render).to[collection.immutable.Seq], space))

    /* Split axioms with more than one trigger set into multiple copies of the same
     * axiom, each with a single trigger. This can avoid incompletenesses due to Z3
     * potentially ignoring all but the first trigger set. (I can't find the post
     * by Nikolaj now, but he described it somewhere, either on Stackoverflow or in
     * Z3's issue tracker).
     */
    case q: Quantification if q.triggers.lengthCompare(1) > 0 =>
      render(And(q.triggers.map(trg => q.copy(triggers = Vector(trg)))))

    /* Handle quantifiers that have at most one trigger set */
    case Quantification(quant, vars, body, triggers, name) =>
      val docVars = ssep((vars map (v => parens(text(sanitize(v.id)) <+> render(v.sort)))).to[collection.immutable.Seq], space)
      val docBody = render(body)
      val docQuant = render(quant)

      val docTriggers =
        ssep(triggers.map(trigger => ssep((trigger.p map render).to[collection.immutable.Seq], space))
                     .map(d => text(":pattern") <+> parens(d)).to[collection.immutable.Seq],
             line)

      val docQid: Cont =
        if (name.isEmpty) nil
        else s":qid |$name|"

      parens(docQuant <+> parens(docVars) <+> parens(text("!") <> nest(defaultIndent, line <> docBody <> line <> docTriggers <> line <> docQid)))

    /* Booleans */

    case uop: Not => renderUnaryOp("not", uop)
    case And(ts) => renderNAryOp("and", ts: _*)
    case Or(ts) => renderNAryOp("or", ts: _*)
    case bop: Implies => renderBinaryOp("implies", bop)
    case bop: Iff => renderBinaryOp("iff", bop)
    case bop: BuiltinEquals => renderBinaryOp("=", bop)

    case bop: CustomEquals => bop.p0.sort match {
      case _: sorts.Seq => renderBinaryOp("$Seq.equal", bop)
      case _: sorts.Set => renderBinaryOp("$Set.equal", bop)
      case _: sorts.Multiset => renderBinaryOp("$Multiset.equal", bop)
      case sort => sys.error(s"Don't know how to translate equality between symbols $sort-typed terms")
    }

    /* Arithmetic */

    case bop: Minus => renderBinaryOp("-", bop)
    case bop: Plus => renderBinaryOp("+", bop)
    case bop: Times => renderBinaryOp("*", bop)
    case bop: Div => renderBinaryOp("div", bop)
    case bop: Mod => renderBinaryOp("mod", bop)

    /* Arithmetic comparisons */

    case bop: Less => renderBinaryOp("<", bop)
    case bop: AtMost => renderBinaryOp("<=", bop)
    case bop: AtLeast => renderBinaryOp(">=", bop)
    case bop: Greater => renderBinaryOp(">", bop)

    /* Permissions */

    case FullPerm() => "$Perm.Write"
    case NoPerm() => "$Perm.No"
    case WildcardPerm(v) => render(v)
    case FractionPerm(n, d) => renderBinaryOp("/", renderAsReal(n), renderAsReal(d))
    case PermLess(t0, t1) => renderBinaryOp("<", render(t0), render(t1))
    case PermAtMost(t0, t1) => renderBinaryOp("<=", render(t0), render(t1))
    case PermPlus(t0, t1) => renderBinaryOp("+", renderAsReal(t0), renderAsReal(t1))
    case PermMinus(t0, t1) => renderBinaryOp("-", renderAsReal(t0), renderAsReal(t1))
    case PermTimes(t0, t1) => renderBinaryOp("*", renderAsReal(t0), renderAsReal(t1))
    case IntPermTimes(t0, t1) => renderBinaryOp("*", renderAsReal(t0), renderAsReal(t1))
    case PermIntDiv(t0, t1) => renderBinaryOp("/", renderAsReal(t0), renderAsReal(t1))
    case PermMin(t0, t1) => renderBinaryOp("$Perm.min", render(t0), render(t1))
    case IsValidPermVar(v) => parens(text("$Perm.isValidVar") <+> render(v))
    case IsReadPermVar(v, ub) => parens(text("$Perm.isReadVar") <+> render(v) <+> render(ub))

    /* Sequences */

    case SeqRanged(t0, t1) => renderBinaryOp("$Seq.range", render(t0), render(t1))
    case SeqSingleton(t0) => parens(text("$Seq.singleton") <+> render(t0))
    case bop: SeqAppend => renderBinaryOp("$Seq.append", bop)
    case uop: SeqLength => renderUnaryOp("$Seq.length", uop)
    case bop: SeqAt => renderBinaryOp("$Seq.index", bop)
    case bop: SeqTake => renderBinaryOp("$Seq.take", bop)
    case bop: SeqDrop => renderBinaryOp("$Seq.drop", bop)
    case bop: SeqIn => renderBinaryOp("$Seq.contains", bop)
    case SeqUpdate(t0, t1, t2) => renderNAryOp("$Seq.update", t0, t1, t2)

    /* Sets */

    case SingletonSet(t0) => parens(text("$Set.singleton ") <+> render(t0))
    case bop: SetAdd => renderBinaryOp("$Set.unionone", bop)
    case uop: SetCardinality => renderUnaryOp("$Set.card", uop)
    case bop: SetDifference => renderBinaryOp("$Set.difference", bop)
    case bop: SetIntersection => renderBinaryOp("$Set.intersection", bop)
    case bop: SetUnion => renderBinaryOp("$Set.union", bop)
    case bop: SetIn =>
      renderBinaryOp("$Set.in", bop)
//      val expandedTerm = SetSubset(SingletonSet(bop.p0), bop.p1)
//      render(expandedTerm)
//      renderBinaryOp("$Map.select", render(bop.p1), render(bop.p0))
    case bop: SetSubset => renderBinaryOp("$Set.subset", bop)
    case bop: SetDisjoint => renderBinaryOp("$Set.disjoint", bop)

    /* Multisets */

    case SingletonMultiset(t0) => parens(text("$Multiset.singleton") <+> render(t0))
    case bop: MultisetAdd => renderBinaryOp("$Multiset.unionone", bop)
    case uop: MultisetCardinality => renderUnaryOp("$Multiset.card", uop)
    case bop: MultisetDifference => renderBinaryOp("$Multiset.difference", bop)
    case bop: MultisetIntersection => renderBinaryOp("$Multiset.intersection", bop)
    case bop: MultisetUnion => renderBinaryOp("$Multiset.union", bop)
    case bop: MultisetSubset => renderBinaryOp("$Multiset.subset", bop)
    case bop: MultisetCount => renderBinaryOp("$Multiset.count", bop)

    /* Quantified Permissions */

    case Domain(id, fvf) => parens(text("$FVF.domain_") <> id <+> render(fvf))

    case Lookup(field, fvf, at) => //fvf.sort match {
//      case _: sorts.PartialFieldValueFunction =>
        parens(text("$FVF.lookup_") <> field <+> render(fvf) <+> render(at))
//      case _: sorts.TotalFieldValueFunction =>
//        render(Apply(fvf, Seq(at)))
//        parens("$FVF.lookup_" <> field <+> render(fvf) <+> render(at))
//      case _ =>
//        sys.error(s"Unexpected sort '${fvf.sort}' of field value function '$fvf' in lookup term '$term'")
//    }



    case PredicateDomain(id, psf) => parens(text("$PSF.domain_") <> id <+> render(psf))

    case PredicateLookup(id, psf, args, formalVars) =>
      var snap:Term = if (args.size == 1) {
        args.apply(0).convert(sorts.Snap)
      } else {
        args.reduce((arg1:Term, arg2:Term) => Combine(arg1, arg2))
      }

      parens(text("$PSF.lookup_") <> id <+> render(psf) <+> render(snap))
/*
    case PsfAfterRelation(id, psf2, psf1) => parens("$PSF.after_" <> id <+> render(psf2) <+> render(psf1))
=======
>>>>>>> other*/
    /* Other terms */

    case First(t) => parens(text("$Snap.first") <+> render(t))
    case Second(t) => parens(text("$Snap.second") <+> render(t))

    case bop: Combine =>
      renderBinaryOp("$Snap.combine", bop)

    case SortWrapper(t, to) =>
      parens(text(sortWrapperName(t.sort, to)) <+> render(t))

    case Distinct(symbols) =>
      parens(text("distinct") <+> ssep((symbols.toSeq map (s => sanitize(s.id): Cont)).to[collection.immutable.Seq], space))

    case Let(bindings, body) =>
      val docBindings = ssep((bindings.toSeq map (p => parens(render(p._1) <+> render(p._2)))).to[collection.immutable.Seq], space)
      parens(text("let") <+> parens(docBindings) <+> render(body))

    case _: MagicWandChunkTerm =>
      sys.error(s"Unexpected term $term cannot be translated to SMTLib code")
/*<<<<<<< local

    case fvf: SummarisingFvfDefinition =>
      render(And(fvf.quantifiedValueDefinitions))
    case psf: SummarisingPsfDefinition =>
      render(And(psf.quantifiedSnapDefinitions))
=======
>>>>>>> other*/
  }

  @inline
  protected def renderUnaryOp(op: String, t: UnaryOp[Term]) =
    parens(text(op) <> nest(defaultIndent, group(line <> render(t.p))))

  @inline
  protected def renderUnaryOp(op: String, doc: Cont) =
    parens(text(op) <> nest(defaultIndent, group(line <> doc)))

  @inline
  protected def renderBinaryOp(op: String, t: BinaryOp[Term]) =
    parens(text(op) <> nest(defaultIndent, group(line <> render(t.p0) <> line <> render(t.p1))))

  @inline
  protected def renderBinaryOp(op: String, left: Cont, right: Cont) =
    parens(text(op) <> nest(defaultIndent, group(line <> left <> line <> right)))

  @inline
  protected def renderNAryOp(op: String, terms: Term*) =
    parens(text(op) <> nest(defaultIndent, group(line <> ssep((terms map render).to[collection.immutable.Seq], line))))

  protected def render(q: Quantifier): Cont = q match {
    case Forall => "forall"
    case Exists => "exists"
  }

  protected def render(literal: Literal): Cont = literal match {
    case IntLiteral(n) =>
      if (n >= 0) n.toString()
      else parens(text("- 0") <+> value(-n))

    case Unit => "$Snap.unit"
    case True() => "true"
    case False() => "false"
    case Null() => "$Ref.null"
    case SeqNil(elementSort) => text("$Seq.empty<") <> render(elementSort) <> ">"
    case EmptySet(elementSort) => text("$Set.empty<") <> render(elementSort) <> ">"
    case EmptyMultiset(elementSort) => text("$Multiset.empty<") <> render(elementSort) <> ">"
  }

  protected def renderAsReal(t: Term): Cont =
    if (t.sort == sorts.Int)
      parens(text("to_real") <+> render(t))
    else
      render(t)

  protected def sortWrapperName(from: Sort, to: Sort): String =
    s"$$SortWrappers.${convert(from)}To${convert(to)}"

  @inline
  private def sanitize(id: Identifier): String = sanitize(id.name)

  private def sanitize(str: String): String = {
    val sanitizedName = sanitizedNamesCache.getOrElseUpdate(str, nameSanitizer.sanitize(str))

    sanitizedName
  }

  def start(): Unit = {
    sanitizedNamesCache = mutable.Map.empty
  }

  def reset(): Unit = {
    sanitizedNamesCache.clear()
  }

  def stop(): Unit = {
    sanitizedNamesCache.clear()
  }
}
