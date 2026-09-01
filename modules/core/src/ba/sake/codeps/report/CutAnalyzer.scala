package ba.sake.codeps.report

import ba.sake.codeps.graph.TarjanScc
import ba.sake.codeps.model.Edge
import ba.sake.tupson.JsonRW
import ba.sake.tupson.{ParseError, ParsingException}
import org.typelevel.jawn.ast.{JNull, JObject, JValue}

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/** Limits for the optional cut investigation of one strongly connected component.
  * Both limits are enforced before every candidate simulation. A report without a
  * budget does not invoke [[CutAnalyzer]] at all.
  */
case class CutAnalysisBudget(timeLimit: FiniteDuration, candidateLimit: Int)

/** Result of an optional cut investigation. `solutions` only contains complete,
  * validated cut sets; partial work is never presented as a solution.
  */
case class CutAnalysis(
    status: String,
    greedyCutEstimate: Option[Int],
    solutions: Seq[Solution],
    examinedCandidates: Int
)

object CutAnalysis:
  val notRequested: CutAnalysis = CutAnalysis("notRequested", None, Seq.empty, 0)

  given JsonRW[CutAnalysis] with
    override def write(value: CutAnalysis): JValue =
      JObject(scala.collection.mutable.Map.from(Seq(
        "status" -> JsonRW[String].write(value.status),
        "greedyCutEstimate" -> (value.greedyCutEstimate match
          case None    => JNull
          case Some(n) => JsonRW[Int].write(n)),
        "solutions" -> JsonRW[Seq[Solution]].write(value.solutions),
        "examinedCandidates" -> JsonRW[Int].write(value.examinedCandidates)
      )))
    override def parse(path: String, jValue: JValue): CutAnalysis =
      val fields = jValue match
        case JObject(value) => value
        case other =>
          throw ParsingException(
            ParseError(path, s"should be Object but it is ${other.valueType.capitalize}", Some(other.render().take(100)))
          )
      def required[T](key: String)(using rw: JsonRW[T]): T =
        fields.get(key) match
          case Some(value) => rw.parse(s"$path.$key", value)
          case None        => throw ParsingException(ParseError(s"$path.$key", "is missing"))
      CutAnalysis(
        required[String]("status"),
        required[Option[Int]]("greedyCutEstimate"),
        required[Seq[Solution]]("solutions"),
        required[Int]("examinedCandidates")
      )

/** Bounded greedy and enumerative feedback-edge investigation for one SCC. */
object CutAnalyzer:

  private val maxCutCandidates = 6
  private val maxSolutions = 3
  private val maxExactEdges = 60

  private case class GreedyPlan(count: Int, edges: Seq[Edge], complete: Boolean)
  private case class EnumerationResult(solutions: Seq[Seq[Edge]], exact: Boolean)

  def analyze(scc: Set[String], edges: Set[Edge], budget: CutAnalysisBudget): CutAnalysis =
    val deadline = deadlineNanos(budget.timeLimit)
    val state = new SearchState(deadline, budget.candidateLimit)
    val internal = edges.toSeq
      .filter(e => scc.contains(e.source) && scc.contains(e.target))
      .sortBy(e => (e.weight, e.source, e.target))

    val greedy = greedyCutPlan(scc, edges, state)
    if state.exceeded then
      CutAnalysis("budgetExceeded", None, Seq.empty, state.examined)
    else
      val enumeration = enumerateSolutions(scc, edges, internal, state)
      val status =
        if state.exceeded then "budgetExceeded"
        else if enumeration.exact then "completedExact"
        else "completedHeuristic"
      val completeSolutions = enumeration.solutions.map(toSolution)
      val withFallback =
        if completeSolutions.nonEmpty then completeSolutions
        else if !state.exceeded && greedy.complete && dissolves(greedy.edges.toSet, scc, edges) then
          // Large SCCs skip exact enumeration. Serialize the greedy plan only
          // after validating it against the original SCC; a cut can split one
          // SCC into several cyclic components, all of which must be dissolved.
          Seq(toSolution(greedy.edges))
        else Seq.empty
      CutAnalysis(status, if greedy.complete then Some(greedy.count) else None, withFallback, state.examined)

  private final class SearchState(val deadline: Long, val candidateLimit: Int):
    var examined: Int = 0
    var exceeded: Boolean = false

    def available: Boolean =
      if exceeded then false
      else if examined >= candidateLimit || System.nanoTime() >= deadline then
        exceeded = true
        false
      else true

    /** Reserve one candidate before running its simulation. */
    def reserve(): Boolean =
      if available then
        examined += 1
        true
      else false

  private def deadlineNanos(timeLimit: FiniteDuration): Long =
    val now = System.nanoTime()
    val nanos = timeLimit.toNanos
    if nanos >= 0L && Long.MaxValue - now < nanos then Long.MaxValue else now + nanos

  private def greedyCutPlan(
      cycle: Set[String],
      edges: Set[Edge],
      state: SearchState
  ): GreedyPlan =
    var trialEdges = edges
    var cuts = 0
    val chosen = mutable.ListBuffer.empty[Edge]
    var cyclicComponents = cyclicComponentsOf(cycle, trialEdges)
    while cyclicComponents.nonEmpty && !state.exceeded do
      // Reconsider every remaining cyclic component after each cut. Keeping
      // only the largest one loses sibling cycles when a cut splits an SCC.
      val candidates = cyclicComponents.toSeq.flatMap { component =>
        trialEdges.toSeq
          .filter(e => component.contains(e.source) && component.contains(e.target))
          .sortBy(e => (e.weight, e.source, e.target))
          .take(maxCutCandidates)
          .flatMap { e =>
            if state.reserve() then Some((e, simulateCut(e, component, trialEdges))) else None
          }
      }
      if candidates.isEmpty && !state.exceeded then
        // A valid SCC with more than one member always has an internal edge.
        // Mark the plan incomplete if a defensive inconsistent graph reaches
        // this branch, so it can never be serialized as a solution.
        return GreedyPlan(cuts, chosen.toSeq, complete = false)
      else if !state.exceeded then
        val best = candidates.minBy { case (e, (effect, newSize)) =>
          (if effect == "resolved" then 0 else if effect == "partial" then 1 else 2,
            newSize, e.weight, e.source, e.target)
        }._1
        trialEdges -= best
        chosen += best
        cuts += 1
        cyclicComponents = cyclicComponentsOf(cycle, trialEdges)
    GreedyPlan(cuts, chosen.toSeq, complete = cyclicComponents.isEmpty && !state.exceeded)

  private def cyclicComponentsOf(nodes: Set[String], edges: Set[Edge]): Seq[Set[String]] =
    TarjanScc.components(nodes, edges).filter(_.size >= 2).toSeq.sortBy(c => (c.size, c.min))

  private def enumerateSolutions(
      scc: Set[String],
      edges: Set[Edge],
      internal: Seq[Edge],
      state: SearchState
  ): EnumerationResult =
    if internal.size > maxExactEdges then return EnumerationResult(Seq.empty, exact = false)
    val found = mutable.ListBuffer.empty[Seq[Edge]]
    var k = 1
    while k <= internal.size && !state.exceeded do
      val remaining = math.max(state.candidateLimit - state.examined, 0)
      val sortLimit = math.min(remaining, 1000)
      val combos =
        // Keep the exact weight ordering for small searches. For larger spaces,
        // stream combinations in deterministic internal-edge order so a time or
        // candidate budget bounds work before a huge sort/materialization.
        if combinationCountAtMost(internal.size, k, sortLimit) <= sortLimit then
          internal.combinations(k).toSeq.sortBy { combo =>
            (combo.map(_.weight).sum, combo.map(e => s"${e.source}->${e.target}").sorted.mkString(","))
          }.iterator
        else internal.combinations(k)
      while combos.hasNext && state.available do
        val combo = combos.next()
        if !found.exists(f => f.toSet.subsetOf(combo.toSet)) && state.reserve() &&
          dissolves(combo.toSet, scc, edges)
        then
          found += combo
          val ranked = found.toSeq.sortBy(solutionKey)
          found.clear()
          found ++= ranked.take(maxSolutions)
      k += 1
    EnumerationResult(found.toSeq.sortBy(solutionKey), exact = !state.exceeded)

  private def solutionKey(edges: Seq[Edge]): (Int, Int, String) =
    (edges.size, edges.map(_.weight).sum, edges.map(e => s"${e.source}->${e.target}").sorted.mkString(","))

  /** Computes n choose k, stopping at `limit + 1` so large searches never
    * materialize their entire combination space merely to decide whether to
    * sort it. */
  private def combinationCountAtMost(n: Int, k: Int, limit: Int): Int =
    if k < 0 || k > n then 0
    else
      var result = 1L
      var i = 1
      while i <= k && result <= limit.toLong do
        result = result * (n - k + i) / i
        i += 1
      if result > limit then limit + 1 else result.toInt

  private def toSolution(edges: Seq[Edge]): Solution =
    Solution(edges.sortBy(e => (e.weight, e.source, e.target)).map(e => CutCandidate(e.source, e.target, e.weight)))

  private def dissolves(cuts: Set[Edge], scc: Set[String], edges: Set[Edge]): Boolean =
    val comps = TarjanScc.components(scc, edges -- cuts)
    !comps.exists(c => c.size >= 2 && c.exists(scc.contains))

  private def simulateCut(e: Edge, scc: Set[String], edges: Set[Edge]): (String, Int) =
    val containing = TarjanScc.components(scc, edges - e)
      .filter(c => c.contains(e.source) || c.contains(e.target))
    val multiSizes = containing.map(_.size).filter(_ >= 2)
    if multiSizes.isEmpty then ("resolved", 1)
    else if multiSizes.max < scc.size then ("partial", multiSizes.max)
    else ("none", scc.size)
