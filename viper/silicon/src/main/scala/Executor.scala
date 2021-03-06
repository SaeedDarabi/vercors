/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.silicon

import org.slf4s.Logging
import viper.silicon.decider.PathConditionStack
import viper.silver.ast
import viper.silver.verifier.errors._
import viper.silver.verifier.reasons._
import viper.silicon.interfaces._
import viper.silicon.interfaces.decider.Decider
import viper.silicon.interfaces.state.{Store, Heap, State, StateFactory, StateFormatter}
import viper.silicon.interfaces.state.factoryUtils.Ø
import viper.silicon.state.terms._
import viper.silicon.state.{FieldChunk, SymbolConvert, DefaultContext, ListBackedHeap}
import viper.silicon.state.terms.perms.IsNonNegative
import viper.silicon.supporters._
import viper.silicon.supporters.qps.QuantifiedChunkSupporter

trait DefaultExecutor[ST <: Store[ST],
                      H <: Heap[H],
                      S <: State[ST, H, S]]
    extends Executor[ST, H, S, DefaultContext[H]]
    { this: Logging with Evaluator[ST, H, S, DefaultContext[H]]
                    with Consumer[ST, H, S, DefaultContext[H]]
                    with Producer[ST, H, S, DefaultContext[H]]
                    with Brancher[ST, H, S, DefaultContext[H]]
                    with MagicWandSupporter[ST, H, S]
                    with LetHandler[ST, H, S, DefaultContext[H]] =>

  private type C = DefaultContext[H]

  protected implicit val manifestH: Manifest[H]

  protected val decider: Decider[ST, H, S, C]
  protected val stateFactory: StateFactory[ST, H, S]
  protected val symbolConverter: SymbolConvert
  protected val heapCompressor: HeapCompressor[ST, H, S, C]
  protected val quantifiedChunkSupporter: QuantifiedChunkSupporter[ST, H, S, C]
  protected val stateFormatter: StateFormatter[ST, H, S, String]
  protected val config: Config
  protected val predicateSupporter: PredicateSupporter[ST, H, S, C]

  import decider.{fresh, assume, locally}
  import stateFactory._
  import symbolConverter.toSort

  private def follow(σ: S, edge: ast.Edge, c: C)
                    (Q: (S, C) => VerificationResult)
                    : VerificationResult = {

    edge match {
      case ce: ast.ConditionalEdge =>
        eval(σ, ce.cond, IfFailed(ce.cond), c)((tCond, c1) =>
        /* TODO: Use FollowEdge instead of IfBranching */
          branch(σ, tCond, c1,
            (c2: C) => exec(σ, ce.dest, c2)(Q),
            (c2: C) => Success()))

      case ue: ast.UnconditionalEdge => exec(σ, ue.dest, c)(Q)
    }
  }

  private def follows(σ: S, edges: Seq[ast.Edge], c: C)
                     (Q: (S, C) => VerificationResult)
                     : VerificationResult = {

    if (edges.isEmpty) {
      Q(σ, c)
    } else
      follows2(σ, edges, c)(Q)
  }

  private def follows2(σ: S, edges: Seq[ast.Edge], c: C)
                      (Q: (S, C) => VerificationResult)
                      : VerificationResult = {

    if (edges.isEmpty) {
      Success()
    } else {
      follow(σ, edges.head, c)(Q) && follows2(σ, edges.tail, c)(Q)
    }
  }

  private def leave(σ: S, block: ast.Block, c: C)
                   (Q: (S, C) => VerificationResult)
                   : VerificationResult = {

    follows(σ, block.succs, c)(Q)
  }

  def exec(σ: S, block: ast.Block, c: C)
          (Q: (S, C) => VerificationResult)
          : VerificationResult = {

    block match {

      case cblock @ ast.ConditionalBlock(stmt, e, thn, els) =>
        exec(σ, stmt, c)((σ1, c1) => {

          val iteLog = new IfThenElseRecord(e, σ, decider.π, c.asInstanceOf[DefaultContext[ListBackedHeap]])

          val thn_edge = cblock.succs.head
          val els_edge = cblock.succs(1)

          val sepIdentifier = SymbExLogger.currentLog().insert(iteLog)

          val iteResult = eval(σ1, thn_edge.cond, IfFailed(thn_edge.cond), c1)((tCond, c2) => {
            iteLog.finish_thnCond()
            val thn_branch_res = branch(σ1, tCond, c2,
              (c3: C) => exec(σ1, thn_edge.dest, c2)((σ_thn, c_thn) => {
                iteLog.finish_thnSubs()
                Q(σ_thn, c_thn)
              }),
              (c3: C) => Success())
            thn_branch_res
          }) && eval(σ1, els_edge.cond, IfFailed(els_edge.cond), c1)((tCond, c2) => {
            iteLog.finish_elsCond()
            val els_branch_res = branch(σ1, tCond, c2,
              (c3: C) => exec(σ1, els_edge.dest, c3)((σ_els, c_els) => {
                iteLog.finish_elsSubs()
                Q(σ_els, c_els)
              }),
              (c3: C) => Success())
            els_branch_res
          })
          SymbExLogger.currentLog().collapse(null, sepIdentifier)
          iteResult
        })

      case block @ ast.StatementBlock(stmt, _) =>
        exec(σ, stmt, c)((σ1, c1) =>
          leave(σ1, block, c1)(Q))

      case lb: ast.LoopBlock =>
        decider.prover.logComment(s"loop at ${lb.pos}")

        /* TODO: We should avoid roundtripping, i.e., parsing a SIL file into an AST,
         *       which is then converted into a CFG, from which we then compute an
         *       AST again.
         */
        val loopStmt = lb.toAst.asInstanceOf[ast.While]
        val notGuard = ast.Not(lb.cond)(lb.cond.pos, lb.cond.info)

        /* Havoc local variables that are assigned to in the loop body but
         * that have been declared outside of it, i.e. before the loop.
         */
        val wvs = (lb.locals.map(_.localVar) ++ lb.writtenVars).distinct.filterNot(_.typ == ast.Wand)
          /* TODO: BUG: Variables declared by LetWand show up in this list, but shouldn't! */

        val γBody = Γ(wvs.foldLeft(σ.γ.values)((map, v) => map.updated(v, fresh(v))))
        val σBody = Σ(γBody, Ø, σ.g) /* Use the old-state of the surrounding block as the old-state of the loop. */

        var phase1data: Vector[(S, PathConditionStack, C)] = Vector.empty
        var phase2data: Vector[(S, PathConditionStack, C)] = Vector.empty

        (locally {
            val mark = decider.setPathConditionMark()
            viper.api.VControl.profile(loopStmt,"Loop: Check specs well-definedness")
            decider.prover.logComment("Loop: Check specs well-definedness")
            produces(σBody, fresh, lb.invs, ContractNotWellformed, c)((σ1, c1) =>
              produce(σ1, fresh, lb.cond, WhileFailed(loopStmt), c1)((σ2, c2) => {
                /* Detect potential contradictions between path conditions from the loop guard and
                 * from the invariant (e.g. due to conditionals)
                 */
                if (!decider.checkSmoke())
                  phase1data = phase1data :+ (σ2, decider.pcs.after(mark), c2)
                Success()}))}
        && locally {
            val mark = decider.setPathConditionMark()
            viper.api.VControl.profile(loopStmt,"Loop: Establish loop invariant")
            decider.prover.logComment("Loop: Establish loop invariant")
            consumes(σ, lb.invs, e => LoopInvariantNotEstablished(e), c)((σ1, _, c1) => {
              phase2data = phase2data :+ (σ1, decider.pcs.after(mark), c1)
              Success()})}
        && {
            decider.prover.logComment("Loop: Verify loop body")
            viper.api.VControl.profile(loopStmt,"Loop: Verify loop body")
            phase1data.foldLeft(Success(): VerificationResult) {
              case (fatalResult: FatalResult, _) => fatalResult
              case (intermediateResult, (σ1, pcs1, c1)) =>
                intermediateResult && locally {
                  assume(pcs1.assumptions)
                  exec(σ1, lb.body, c1)((σ2, c2) =>
                    {viper.api.VControl.profile(loopStmt,"Loop: Re-Establish loop invariant")
                    consumes(σ2, lb.invs, e => LoopInvariantNotPreserved(e), c2)((σ3, _, c3) =>
                      Success())})}}}
        && {
            viper.api.VControl.profile(loopStmt,"Loop: Continue after loop")
            decider.prover.logComment("Loop: Continue after loop")
            phase2data.foldLeft(Success(): VerificationResult) {
              case (fatalResult: FatalResult, _) => fatalResult
              case (intermediateResult, (σ1, pcs1, c1)) =>
                intermediateResult && locally {
                  assume(pcs1.assumptions)
                  produces(σ1 \ γBody, fresh,  lb.invs :+ notGuard, _ => WhileFailed(loopStmt), c1)((σ2, c2) =>
                    /* Detect potential contradictions (as before) */
                    if (decider.checkSmoke())
                      Success() /* TODO: Mark branch as dead? */
                    else
                      leave(σ2, lb, c2)(Q))}}})

        case frp @ ast.ConstrainingBlock(vars, body, succ) =>
          val arps = vars map σ.γ.apply
          val c1 = c.setConstrainable(arps, true)
          exec(σ, body, c1)((σ1, c2) =>
            leave(σ1, frp, c2.setConstrainable(arps, false))(Q))
    }
  }

  def execs(σ: S, stmts: Seq[ast.Stmt], c: C)
           (Q: (S, C) => VerificationResult)
           : VerificationResult =

    if(stmts.nonEmpty)
      exec(σ, stmts.head, c)((σ1, c1) =>
        execs(σ1, stmts.tail, c1)(Q))
    else
      Q(σ, c)

  def exec(σ: S, stmt: ast.Stmt, c: C)
          (Q: (S, C) => VerificationResult)
          : VerificationResult = {
    val sepIdentifier = SymbExLogger.currentLog().insert(new ExecuteRecord(stmt, σ, decider.π, c.asInstanceOf[DefaultContext[ListBackedHeap]]))
    exec2(σ, stmt, c)((σ1, c1) => {
      SymbExLogger.currentLog().collapse(stmt, sepIdentifier)
      Q(σ1, c1)})
  }

  def exec2(σ: S, stmt: ast.Stmt, c: C)
          (Q: (S, C) => VerificationResult)
          : VerificationResult = {

    /* For debugging-purposes only */
    stmt match {
      case _: ast.Seqn =>
      case _ =>
        log.debug(s"\nEXECUTE ${utils.ast.sourceLineColumn(stmt)}: $stmt")
        log.debug(stateFormatter.format(σ, decider.π))
        decider.prover.logComment("[exec]")
        decider.prover.logComment(stmt.toString())
    }

    val executed = stmt match {
      case ast.Seqn(stmts) =>
        execs(σ, stmts, c)(Q)

      case label @ ast.Label(name, invs) =>
        val c1 = c.copy(oldHeaps = c.oldHeaps + (name -> σ.h))
        Q(σ, c1)

      case ass @ ast.LocalVarAssign(v, rhs) =>
        viper.api.VControl.profile(stmt,"local assignment")
        v.typ match {
          case ast.Wand =>
            assert(rhs.isInstanceOf[ast.MagicWand], s"Expected magic wand but found $rhs (${rhs.getClass.getName}})")
            val wand = rhs.asInstanceOf[ast.MagicWand]
            val pve = LetWandFailed(ass)
            magicWandSupporter.createChunk(σ, wand, pve, c)((chWand, c1) =>
              Q(σ \+ (v, MagicWandChunkTerm(chWand)), c))
          case _ =>
            eval(σ, rhs, AssignmentFailed(ass), c)((tRhs, c1) => {
              val t = ssaifyRhs(tRhs, v.name, v.typ)
              Q(σ \+ (v, t), c1)})
        }

      /* Assignment for a field that contains quantified chunks */
      case ass @ ast.FieldAssign(fa @ ast.FieldAccess(eRcvr, field), rhs)
              if c.qpFields.contains(field) =>
        viper.api.VControl.profile(stmt,"heap assignment (quantified)")
        val pve = AssignmentFailed(ass)
        eval(σ, eRcvr, pve, c)((tRcvr, c1) =>
          eval(σ, rhs, pve, c1)((tRhs, c2) => {
            val hints = quantifiedChunkSupporter.extractHints(None, None, tRcvr)
            val chunkOrderHeuristics = quantifiedChunkSupporter.hintBasedChunkOrderHeuristic(hints)
            quantifiedChunkSupporter.splitSingleLocation(σ, σ.h, field, tRcvr, FullPerm(), chunkOrderHeuristics, c2) {
              case Some((h1, _, _, c3)) =>
                val (fvf, optFvfDef) = quantifiedChunkSupporter.createFieldValueFunction(field, tRcvr, tRhs)
                optFvfDef.foreach(fvfDef => assume(fvfDef.domainDefinitions ++ fvfDef.valueDefinitions))
                val ch = quantifiedChunkSupporter.createSingletonQuantifiedChunk(tRcvr, field.name, fvf, FullPerm())
                Q(σ \ h1 \+ ch, c3)
              case None =>
                Failure(pve dueTo InsufficientPermission(fa))}}))

      case ass @ ast.FieldAssign(fa @ ast.FieldAccess(eRcvr, field), rhs) =>
        viper.api.VControl.profile(stmt,"heap assignment")
        val pve = AssignmentFailed(ass)
        eval(σ, eRcvr, pve, c)((tRcvr, c1) =>
          eval(σ, rhs, pve, c1)((tRhs, c2) =>
            chunkSupporter.withChunk(σ, σ.h, field.name, Seq(tRcvr), Some(FullPerm()), fa, pve, c2)((fc, c3) => {
              val t = ssaifyRhs(tRhs, field.name, field.typ)
              Q(σ \- fc \+ FieldChunk(tRcvr, field.name, tRhs, fc.perm), c3)})))

      case ast.NewStmt(v, fields) =>
        viper.api.VControl.profile(stmt,"new")
        val tRcvr = fresh(v)
        assume(tRcvr !== Null())
        /* TODO: Verify similar to the code in DefaultProducer/ast.FieldAccessPredicate - unify */
        val newChunks = fields map (field => {
          val p = FullPerm()
          val s = fresh(field.name, toSort(field.typ))
          if (c.qpFields.contains(field)) {
            val (fvf, optFvfDef) = quantifiedChunkSupporter.createFieldValueFunction(field, tRcvr, s)
            optFvfDef.foreach(fvfDef => assume(fvfDef.domainDefinitions ++ fvfDef.valueDefinitions))
            quantifiedChunkSupporter.createSingletonQuantifiedChunk(tRcvr, field.name, fvf, p)
          } else
            FieldChunk(tRcvr, field.name, s, p)})
        val σ1 = σ \+ (v, tRcvr) \+ H(newChunks)
        val ts = state.utils.computeReferenceDisjointnesses[ST, H, S](σ1, tRcvr)
          /* Calling computeReferenceDisjointnesses with the updated state σ1 ensures that
           * tRcvr is constrained to be different from (ref-typed) fields of tRcvr to which
           * permissions have been gained.
           * Note that we do not constrain the (ref-typed) fields to be mutually disjoint.
           */
        assume(And(ts))
        Q(σ1, c)

      case ast.Fresh(vars) =>
        viper.api.VControl.profile(stmt,"fresh vars")
        val (arps, arpConstraints) =
          vars.map(v => (v, decider.freshARP()))
              .map{case (variable, (value, constrain)) => ((variable, value), constrain)}
              .unzip
        val γ1 = Γ(σ.γ.values ++ arps)
          /* It is crucial that the (var -> term) mappings in arps override
           * already existing bindings for the same vars when they are added
           * (via ++).
           */
        assume(toSet(arpConstraints))
        Q(σ \ γ1, c)

      case inhale @ ast.Inhale(a) =>
        viper.api.VControl.profile(inhale,"inhale")
        a match {
        case _: ast.FalseLit =>
          /* We're done */
          Success()
        case _ =>
          produce(σ, fresh, a, InhaleFailed(inhale), c)((σ1, c1) =>
            Q(σ1, c1))
      }

      case exhale @ ast.Exhale(a) =>
        viper.api.VControl.profile(exhale,"exhale")
        val pve = ExhaleFailed(exhale)
        consume(σ, a, pve, c)((σ1, _, c1) =>
          Q(σ1, c1))

      case assert @ ast.Assert(a) =>
        viper.api.VControl.profile(assert,"assertion or refute")
        val pve = AssertFailed(assert)

        a match {
          case ast.Not(expr) if a.info.isInstanceOf[viper.api.RefuteInfo[_]] =>
            if (decider.checkSmoke()) {
              // Unreachable branches are OK. 
              Success()
            } else {
              // Add to reachable set and continue.
              viper.api.Reachable.reachable+=a.info
              if (config.disableSubsumption()) {
                val r =
                  consume(σ, a, pve, c)((σ1, _, c1) =>
                    Success())
                r && Q(σ, c)
              } else
                consume(σ, a, pve, c)((σ1, _, c1) =>
                  Q(σ, c1))
            }
          /* "assert true" triggers a heap compression. */
          case _: ast.TrueLit =>
            heapCompressor.compress(σ, σ.h, c)
            Q(σ, c)

          /* "assert false" triggers a smoke check. If successful, we backtrack. */
          case _: ast.FalseLit =>
            decider.tryOrFail[S](σ, c)((σ1, c1, QS, QF) => {
              if (decider.checkSmoke())
                  QS(σ1, c1)
              else
                  QF(Failure(pve dueTo AssertionFalse(a)))
              })((_, _) => Success())

          case _ =>
            if (config.disableSubsumption()) {
              val r =
                consume(σ, a, pve, c)((σ1, _, c1) =>
                  Success())
              r && Q(σ, c)
            } else
              consume(σ, a, pve, c)((σ1, _, c1) =>
                Q(σ, c1))
        }

      case call @ ast.MethodCall(methodName, eArgs, lhs) =>
        viper.api.VControl.profile(call,"method call")
        val meth = c.program.findMethod(methodName)
        val pvefCall = (_: ast.Exp) =>  CallFailed(call)
        val pvefPre = (_: ast.Exp) =>  PreconditionInCallFalse(call)
        val mcLog = new MethodCallRecord(call, σ, decider.π, c.asInstanceOf[DefaultContext[ListBackedHeap]])
        val sepIdentifier = SymbExLogger.currentLog().insert(mcLog)
        evals(σ, eArgs, pvefCall, c)((tArgs, c1) => {
          mcLog.finish_parameters()
          val c2 = c1.copy(recordVisited = true)
          val insγ = Γ(meth.formalArgs.map(_.localVar).zip(tArgs))
          consumes(σ \ insγ, meth.pres, pvefPre, c2)((σ1, _, c3) => {
            mcLog.finish_precondition()
            val outs = meth.formalReturns.map(_.localVar)
            val outsγ = Γ(outs.map(v => (v, fresh(v))).toMap)
            val σ2 = σ1 \+ outsγ \ (g = σ.h)
            produces(σ2, fresh, meth.posts, pvefCall, c3)((σ3, c4) => {
              mcLog.finish_postcondition()
              val lhsγ = Γ(lhs.zip(outs)
                              .map(p => (p._1, σ3.γ(p._2))).toMap)
              val c5 = c4.copy(recordVisited = c1.recordVisited)
              SymbExLogger.currentLog().collapse(null, sepIdentifier)
              Q(σ3 \ (g = σ.g, γ = σ.γ + lhsγ), c5)})})})

      case fold @ ast.Fold(ast.PredicateAccessPredicate(ast.PredicateAccess(eArgs, predicateName), ePerm)) =>
        viper.api.VControl.profile(fold,"fold")
        val predicate = c.program.findPredicate(predicateName)
        val pve = FoldFailed(fold)
        evals(σ, eArgs, _ => pve, c)((tArgs, c1) =>
            eval(σ, ePerm, pve, c1)((tPerm, c2) =>
              decider.assert(σ, IsNonNegative(tPerm)){
                case true =>
                  //handles both quantified and unquantified predicates
                  predicateSupporter.fold(σ, predicate, tArgs, tPerm, pve, c2)(Q)
                case false =>
                  Failure(pve dueTo NegativePermission(ePerm))}))

      case unfold @ ast.Unfold(ast.PredicateAccessPredicate(pa @ ast.PredicateAccess(eArgs, predicateName), ePerm)) =>
        viper.api.VControl.profile(unfold,"unfold")
        val predicate = c.program.findPredicate(predicateName)
        val pve = UnfoldFailed(unfold)
        evals(σ, eArgs, _ => pve, c)((tArgs, c1) =>
          eval(σ, ePerm, pve, c1)((tPerm, c2) =>
            decider.assert(σ, IsNonNegative(tPerm)){
              case true =>
                //handles both quantified and unquantified predicates
                predicateSupporter.unfold(σ, predicate, tArgs, tPerm, pve, c2, pa)(Q)
              case false =>
                Failure(pve dueTo NegativePermission(ePerm))}))

      case pckg @ ast.Package(wand) =>
        viper.api.VControl.profile(pckg,"package")
        val pve = PackageFailed(pckg)
        val c0 = c.copy(reserveHeaps = H() :: σ.h :: Nil,
                        recordEffects = true,
                        consumedChunks = Nil :: Nil,
                        letBoundVars = Nil)
        magicWandSupporter.packageWand(σ, wand, pve, c0)((chWand, c1) => {
          assert(c1.reserveHeaps.length == 1) /* c1.reserveHeap is expected to be [σ.h'], i.e. the remainder of σ.h */
          val h1 = c1.reserveHeaps.head
          val c2 = c1.copy(exhaleExt = false,
                           reserveHeaps = Nil,
                           lhsHeap = None,
                           recordEffects = false,
                           consumedChunks = Stack(),
                           letBoundVars = Nil)
          Q(σ \ (h1 + chWand), c2)})

      case apply @ ast.Apply(e) =>
        viper.api.VControl.profile(apply,"apply")
        /* TODO: Try to unify this code with that from DefaultConsumer/applying */

        val pve = ApplyFailed(apply)

        def QL(σ1: S, γ: ST, wand: ast.MagicWand, c1: C) = {
          /* The given heap is not σ.h, but rather the consumed portion only. However,
           * using σ.h should not be a problem as long as the heap that is used as
           * the given-heap while checking self-framingness of the wand is the heap
           * described by the left-hand side.
           */
          consume(σ1 \ γ, wand.left, pve, c1)((σ2, _, c2) => {
            val c2a = c2.copy(lhsHeap = Some(σ1.h))
            produce(σ2, fresh, wand.right, pve, c2a)((σ3, c3) => {
              val c4 = c3.copy(lhsHeap = None)
              heapCompressor.compress(σ3, σ3.h, c4)
              Q(σ3 \ σ1.γ, c4)})})}

        e match {
          case wand: ast.MagicWand =>
            consume(σ, wand, pve, c)((σ1, _, c1) => {
              QL(σ1, σ1.γ, wand, c1)})

          case v: ast.AbstractLocalVar =>
            val chWand = σ.γ(v).asInstanceOf[MagicWandChunkTerm].chunk
            magicWandSupporter.getMatchingChunk(σ, σ.h, chWand, c) match {
              case Some(ch) =>
                QL(σ \- ch, Γ(chWand.bindings), chWand.ghostFreeWand, c)
              case None =>
                Failure(pve dueTo NamedMagicWandChunkNotFound(v))}

          case _ => sys.error(s"Expected a magic wand, but found node $e")}


      /* These cases should not occur when working with the CFG-representation of the program. */
      case   _: ast.Goto
           | _: ast.If
           | _: ast.Label
           | _: ast.Seqn
           | _: ast.Constraining
           | _: ast.While => sys.error(s"Unexpected statement (${stmt.getClass.getName}): $stmt")
    }

    executed
  }

   private def ssaifyRhs(rhs: Term, name: String, typ: ast.Type): Term =
     rhs match {
       case _: Var | _: Literal =>
         /* Cheap (and likely to succeed) matches come first */
         rhs

       case _ if    rhs.existsDefined { case t if TriggerGenerator.isForbiddenInTrigger(t) => true }
                 || rhs.isInstanceOf[WildcardPerm] /* Fixes issue #110 (somewhat indirectly) */
            =>

         val t = fresh(name, toSort(typ))
         assume(t === rhs)

         t

       case _ =>
         /* Catch-all case */
         rhs
     }
}
