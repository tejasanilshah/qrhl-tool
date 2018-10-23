package qrhl

import java.nio.file.{Files, Path, Paths}

import info.hupel.isabelle.Operation
import info.hupel.isabelle.hol.HOLogic
import info.hupel.isabelle.pure.{App, Const, Term, Typ => ITyp}
import info.hupel.isabelle.pure
import org.log4s
import qrhl.isabelle.Isabelle
import qrhl.isabelle.Isabelle.Thm
import qrhl.logic._
import qrhl.toplevel.{Command, Parser, ParserContext}

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks

sealed trait Subgoal {
  def simplify(isabelle: Isabelle.Context, facts: List[String]): Subgoal

  /** Checks whether all isabelle terms in this goal are well-typed.
    * Should always succeed, unless there are bugs somewhere. */
  def checkWelltyped(context: Isabelle.Context): Unit

  /** This goal as a boolean expression. If it cannot be expressed in Isabelle, a different,
    * logically weaker expression is returned. */
  def toExpression(context:Isabelle.Context): Expression

  def checkVariablesDeclared(environment: Environment): Unit

  def containsAmbientVar(x: String) : Boolean

  @tailrec
  final def addAssumptions(assms: List[Expression]): Subgoal = assms match {
    case Nil => this
    case a::as => addAssumption(a).addAssumptions(as)
  }

  def addAssumption(assm: Expression): Subgoal
}

object Subgoal {
  private val logger = log4s.getLogger

  def printOracles(thms : Thm*): Unit = {
    for (thm <- thms)
      thm.show_oracles()
  }

  def apply(context: Isabelle.Context, e : Expression) : Subgoal = {
    val assms = new ListBuffer[Expression]()
    var t = e.isabelleTerm
    Breaks.breakable {
      while (true) {
        t match {
          case App(App(Const(Isabelle.implies.name, _), a), b) =>
            assms.append(Expression(e.typ, a))
            t = b
          case _ => Breaks.break()
        }
      }
    }

    t match {
      case App(App(App(App(Const(Isabelle.qrhl.name,_),pre),left),right),post) =>
        val pre2 = Expression.decodeFromExpression(context,pre)
        val post2 = Expression.decodeFromExpression(context,post)
        val left2 = Statement.decodeFromListTerm(context, left)
        val right2 = Statement.decodeFromListTerm(context, right)
        QRHLSubgoal(left2,right2,pre2,post2,assms.toList)
      case _ =>
        AmbientSubgoal(e)
    }
  }
}

object QRHLSubgoal {
  private val logger = log4s.getLogger
}

final case class QRHLSubgoal(left:Block, right:Block, pre:Expression, post:Expression, assumptions:List[Expression]) extends Subgoal {
  override def toString: String = {
    val assms = if (assumptions.isEmpty) "" else
      s"Assumptions:\n${assumptions.map(a => s"* $a\n").mkString}\n"
    s"${assms}Pre:   $pre\nLeft:  ${left.toStringNoParens}\nRight: ${right.toStringNoParens}\nPost:  $post"
  }

  override def checkVariablesDeclared(environment: Environment): Unit = {
    for (x <- pre.variables)
      if (!environment.variableExistsForPredicate(x))
        throw UserException(s"Undeclared variable $x in precondition")
    for (x <- post.variables)
      if (!environment.variableExistsForPredicate(x))
        throw UserException(s"Undeclared variable $x in postcondition")
    for (x <- left.variablesDirect)
      if (!environment.variableExistsForProg(x))
        throw UserException(s"Undeclared variable $x in left program")
    for (x <- right.variablesDirect)
      if (!environment.variableExistsForProg(x))
        throw UserException(s"Undeclared variable $x in left program")
    for (a <- assumptions; x <- a.variables)
      if (!environment.variableExists(x))
        throw UserException(s"Undeclared variable $x in assumptions")
  }

  override def toExpression(context: Isabelle.Context): Expression = {
    val leftTerm = left.programListTerm(context)
    val rightTerm = right.programListTerm(context)
    val preTerm = pre.encodeAsExpression(context)
    val postTerm = post.encodeAsExpression(context)
    val qrhl : Term = Isabelle.qrhl $ preTerm $ leftTerm $ rightTerm $ postTerm
    val term = assumptions.foldRight[Term](qrhl) { HOLogic.imp $ _.isabelleTerm $ _ }
    Expression(Isabelle.boolT, term)
  }

  /** Not including ambient vars in nested programs (via Call) */
  override def containsAmbientVar(x: String): Boolean = {
    pre.variables.contains(x) || post.variables.contains(x) ||
      left.variablesDirect.contains(x) || right.variablesDirect.contains(x) ||
      assumptions.exists(_.variables.contains(x))
  }

  override def addAssumption(assm: Expression): QRHLSubgoal = {
    assert(assm.typ==HOLogic.boolT)
    QRHLSubgoal(left,right,pre,post,assm::assumptions)
  }

  /** Checks whether all isabelle terms in this goal are well-typed.
    * Should always succeed, unless there are bugs somewhere. */
  override def checkWelltyped(context:Isabelle.Context): Unit = {
    for (a <- assumptions) a.checkWelltyped(context, HOLogic.boolT)
    left.checkWelltyped(context)
    right.checkWelltyped(context)
    pre.checkWelltyped(context, Isabelle.predicateT)
    post.checkWelltyped(context, Isabelle.predicateT)
  }

  override def simplify(isabelle: Isabelle.Context, facts: List[String]): QRHLSubgoal = {
//    if (assumptions.nonEmpty) QRHLSubgoal.logger.warn("Not using assumptions for simplification")
    val (assms2,thms) = assumptions.map(_.simplify(isabelle,facts)).unzip
    val assms3: List[Expression] = assms2.filter(_.isabelleTerm!=HOLogic.True)
    val (pre2,thm2) = pre.simplify(isabelle,facts)
    val (post2,thm3) = post.simplify(isabelle,facts)
    Subgoal.printOracles(thm2::thm3::thms : _*)
    QRHLSubgoal(left, right, pre2, post2, assms2)
  }
}

final case class AmbientSubgoal(goal: Expression) extends Subgoal {
  override def toString: String = goal.toString

  override def checkVariablesDeclared(environment: Environment): Unit =
    for (x <- goal.variables)
      if (!environment.variableExists(x))
        throw UserException(s"Undeclared variable $x")

  /** This goal as a boolean expression. */
  override def toExpression(context: Isabelle.Context): Expression = goal

  override def containsAmbientVar(x: String): Boolean = goal.variables.contains(x)

  override def addAssumption(assm: Expression): AmbientSubgoal = {
    assert(assm.typ == HOLogic.boolT)
    AmbientSubgoal(assm.implies(goal))
  }

  /** Checks whether all isabelle terms in this goal are well-typed.
    * Should always succeed, unless there are bugs somewhere. */
  override def checkWelltyped(context: Isabelle.Context): Unit = goal.checkWelltyped(context,HOLogic.boolT)

  override def simplify(isabelle: Isabelle.Context, facts: List[String]): AmbientSubgoal = {
    val (term, thm) = goal.simplify(isabelle, facts)
    Subgoal.printOracles(thm)
    AmbientSubgoal(term)
  }
}

trait Tactic {
  def apply(state: State, goal : Subgoal) : List[Subgoal]
}

case class UserException(msg:String) extends RuntimeException(msg)

/** A path together with a last-modification time. */
class FileTimeStamp(val file:Path) {
  private val time = Files.getLastModifiedTime(file)
  /** Returns whether the file has changed since the FileTimeStamp was created. */
  def changed : Boolean = time!=Files.getLastModifiedTime(file)

  override def toString: String = s"$file@$time"
}

class State private (val environment: Environment,
                     val goal: List[Subgoal],
                     val currentLemma: Option[(String,Expression)],
                     private val _isabelle: Option[Isabelle.Context],
                     val dependencies: List[FileTimeStamp],
                     val currentDirectory: Path) {
  def isabelle: Isabelle.Context = _isabelle match {
    case Some(isa) => isa
    case None => throw UserException(Parser.noIsabelleError)
  }
  def hasIsabelle: Boolean = _isabelle.isDefined

  def qed: State = {
    assert(currentLemma.isDefined)
    assert(goal.isEmpty)

    val (name,prop) = currentLemma.get
    val isa = if (name!="") _isabelle.map(_.addAssumption(name,prop.isabelleTerm)) else _isabelle
    copy(isabelle=isa, currentLemma=None)
  }

  def declareProgram(name: String, program: Block): State = {
    for (x <- program.variablesDirect)
      if (!environment.variableExistsForProg(x))
        throw UserException(s"Undeclared variable $x in program")

    if (_isabelle.isEmpty) throw UserException("Missing isabelle command.")
    if (this.environment.variableExists(name))
      throw UserException(s"Name $name already used for a variable or program.")
    val isa = _isabelle.get.declareVariable(name, Isabelle.programT)

    copy(environment = environment.declareProgram(name, program))
  }

  def declareAdversary(name: String, cvars: Seq[CVariable], qvars: Seq[QVariable], numOracles : Int): State = {
    copy(environment = environment.declareAdversary(name, cvars, qvars, numOracles))
  }


  def applyTactic(tactic:Tactic) : State = goal match {
    case Nil =>
      throw UserException("No pending proof")
    case (subgoal::subgoals) =>
      copy(goal=tactic.apply(this,subgoal)++subgoals)
  }

  private def copy(environment:Environment=environment,
                   goal:List[Subgoal]=goal,
                   isabelle:Option[Isabelle.Context]=_isabelle,
                   dependencies:List[FileTimeStamp]=dependencies,
                   currentLemma:Option[(String,Expression)]=currentLemma,
                   currentDirectory:Path=currentDirectory) : State =
    new State(environment=environment, goal=goal, _isabelle=isabelle,
      currentLemma=currentLemma, dependencies=dependencies, currentDirectory=currentDirectory)

  def changeDirectory(dir:Path): State = {
    assert(dir!=null)
    if (dir==currentDirectory) return this
    if (!Files.isDirectory(dir)) throw UserException(s"Non-existent directory: $dir")
    if (hasIsabelle) throw UserException("Cannot change directory after loading Isabelle")
    copy(currentDirectory=dir)
  }

  def openGoal(name:String, goal:Subgoal) : State = this.currentLemma match {
    case None =>
      goal.checkVariablesDeclared(environment)
      copy(goal=List(goal), currentLemma=Some((name,goal.toExpression(_isabelle.get))))
    case _ => throw UserException("There is still a pending proof.")
  }

  override def toString: String = goal match {
    case Nil => "No current goal."
    case _ =>
      s"${goal.size} subgoals:\n\n" + goal.mkString("\n\n")
  }

  lazy val parserContext = ParserContext(isabelle=_isabelle, environment=environment)

  def parseCommand(str:String): Command = {
    implicit val parserContext: ParserContext = this.parserContext
    Parser.parseAll(Parser.command,str) match {
      case Parser.Success(cmd2,_) => cmd2
      case res @ Parser.NoSuccess(msg, _) =>
        throw UserException(msg)
    }
  }

  def parseExpression(typ:pure.Typ, str:String): Expression = {
    implicit val parserContext: ParserContext = this.parserContext
    Parser.parseAll(Parser.expression(typ),str) match {
      case Parser.Success(cmd2,_) => cmd2
      case res @ Parser.NoSuccess(msg, _) =>
        throw UserException(msg)
    }
  }

  def parseBlock(str:String): Block = {
    implicit val parserContext: ParserContext = this.parserContext
    Parser.parseAll(Parser.block,str) match {
      case Parser.Success(cmd2,_) => cmd2
      case res @ Parser.NoSuccess(msg, _) =>
        throw UserException(msg)
    }
  }

  def loadIsabelle(path:String, theory:Option[String]) : State = {
    if (!_isabelle.isEmpty)
      throw UserException("Only one isabelle-command allowed")
    val isa = new Isabelle(path)
    // since this is likely to happen when an existing Isabelle is reloaded, give the GC a chance to remove that existing Isabelle
    System.gc()
    loadIsabelle(isa,theory)
  }

  def loadIsabelle(isabelle: Isabelle, theory:Option[String]) : State = {
    val (isa,files) = theory match {
      case None =>
        (isabelle.getQRHLContextWithFiles(), dependencies)
      case Some(thy) =>
        val filename = currentDirectory.resolve(thy+".thy")
//        println("State.loadIsabelle",thy,currentDirectory,filename)
//        val thyname = currentDirectory.resolve(thy)
        (isabelle.getQRHLContextWithFiles(filename), new FileTimeStamp(filename) :: dependencies)
    }
    copy(isabelle = Some(isa), dependencies=files)
  }

  def filesChanged : List[Path] = {
    dependencies.filter(_.changed).map(_.file)
  }

  private def declare_quantum_variable(isabelle: Isabelle.Context, name: String, typ: ITyp) : Isabelle.Context = {
    isabelle.map(id => isabelle.isabelle.invoke(State.declare_quantum_variable, (name,typ,id)))
  }

  private def declare_classical_variable(isabelle: Isabelle.Context, name: String, typ: ITyp) : Isabelle.Context = {
    isabelle.map(id => isabelle.isabelle.invoke(State.declare_classical_variable, (name,typ,id)))
  }

  def declareVariable(name: String, typ: pure.Typ, quantum: Boolean = false): State = {
    val newEnv = environment.declareVariable(name, typ, quantum = quantum)
      .declareAmbientVariable("var_"+name, typ)
      .declareAmbientVariable("var_"+Variable.index1(name), typ)
      .declareAmbientVariable("var_"+Variable.index2(name), typ)
    if (_isabelle.isEmpty) throw UserException("Missing isabelle command.")
    val isa = _isabelle.get
//    val typ1 = typ.isabelleTyp
//    val typ2 = if (quantum) Type("QRHL_Core.variable",List(typ1)) else typ1
    val newIsa =
      if (quantum)
        declare_quantum_variable(isa, name, typ)
      else
        declare_classical_variable(isa, name, typ)

    copy(environment = newEnv, isabelle = Some(newIsa))
  }

  def declareAmbientVariable(name: String, typ: pure.Typ): State = {
    val newEnv = environment.declareAmbientVariable(name, typ)
    if (_isabelle.isEmpty) throw UserException("Missing isabelle command.")
    val isa = _isabelle.get.declareVariable(name, typ)
    copy(environment = newEnv, isabelle = Some(isa))
  }
}

object State {
  val empty = new State(environment=Environment.empty,goal=Nil,_isabelle=None,
    dependencies=Nil, currentLemma=None, currentDirectory=Paths.get(""))
//  private[State] val defaultIsabelleTheory = "QRHL"

  val declare_quantum_variable: Operation[(String, ITyp, BigInt), BigInt] =
    Operation.implicitly[(String,ITyp,BigInt), BigInt]("declare_quantum_variable")

  val declare_classical_variable: Operation[(String, ITyp, BigInt), BigInt] =
    Operation.implicitly[(String,ITyp,BigInt), BigInt]("declare_classical_variable")
}
