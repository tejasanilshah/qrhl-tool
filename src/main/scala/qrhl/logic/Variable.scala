package qrhl.logic

import qrhl.isabellex.{IsabelleConsts, IsabelleX}
import IsabelleX.{globalIsabelle => GIsabelle}
import de.unruh.isabelle.pure.{App, Const, Free, Term, Typ}
import hashedcomputation.{Hash, HashTag, Hashable, HashedValue, RawHash}
import qrhl.AllSet

import scala.collection.immutable.ListSet
import scala.concurrent.ExecutionContext

// Implicits
import hashedcomputation.Implicits._
import qrhl.isabellex.Implicits._
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.mlvalue.Implicits._

// Variables
sealed trait Variable extends HashedValue {
  def rename(name: String): Variable

  /** Renames this variable.
   * @param renaming - the substitution as an association list. Must not contain pairs (x,x), nor two pairs (x,y), (x,y'). */
  def substitute(renaming: List[(Variable, Variable)]): Variable =
    renaming.find { case (x,y) => x==this }match {
      case None => this
      case Some((x,y)) => y
    }

  def isClassical: Boolean
  def isQuantum: Boolean

  val name:String
  /** Name of the variable on Isabelle side (prefixed with var_ for classical variables) */
  val variableName: String
  def index1: Variable
  def index2: Variable
  def index(left:Boolean): Variable = if (left) index1 else index2
  def variableTyp: Typ = GIsabelle.variableT(valueTyp)
  def valueTyp : Typ
//  @deprecated("use valueType / variableTyp","") def typ : Typ
  def variableTerm(implicit isa: de.unruh.isabelle.control.Isabelle, ec: ExecutionContext): Term = Free(variableName,variableTyp)
  def classicalQuantumWord : String
}

object Variable {
  implicit object ordering extends Ordering[Variable] {
    // Implicits
    import qrhl.isabellex.IsabelleX.globalIsabelle.isabelleControl
    import scala.concurrent.ExecutionContext.Implicits.global

    def compareSame(x: Variable, y:Variable): Int = {
      val nameComp = Ordering.String.compare(x.name, y.name)
      if (nameComp==0)
        GIsabelle.Ops.compareTyps(x.valueTyp, y.valueTyp).retrieveNow
      else
        nameComp
    }
    override def compare(x: Variable, y: Variable): Int = (x,y) match {
      case (_ : QVariable, _ : CVariable) => +1
      case (_ : CVariable, _ : QVariable) => -1
      case _ => compareSame(x,y)
    }
  }

  def quantum(vars: ListSet[Variable]): ListSet[QVariable] = vars collect { case v : QVariable => v }
  def quantum(vars: Traversable[Variable]): Traversable[QVariable] = vars collect { case v : QVariable => v }
  def quantum(vars: Set[Variable]): Set[QVariable] = vars collect { case v : QVariable => v }
  def classical(vars: ListSet[Variable]): ListSet[CVariable] = vars collect { case v : CVariable => v }
  def classical(vars: Traversable[Variable]): Traversable[CVariable] = vars collect { case v : CVariable => v }
  def classical(vars: Set[Variable]): Set[CVariable] = vars collect { case v : CVariable => v }

  //  def varlistToString(vars: List[Variable]) = vars match {
//    case Nil => "()"
//    case List(x) => x.name;
//    case _ => s"(${vars.mkString(",")})"
//  }

  def vartermToString[A](toStr:A=>String, vars: VarTerm[A]): String = vars match {
    case VTUnit => "()"
    case VTSingle(x) => toStr(x)
    case VTCons(VTSingle(x),xs) => toStr(x) + "," + vartermToString(toStr,xs)
    case VTCons(VTUnit,xs) => "()," + vartermToString(toStr,xs)
    case VTCons(a,b) => s"(${vartermToString(toStr,a)}),${vartermToString(toStr,b)}"
  }

  def vartermToString(vars: VarTerm[Variable]): String = vartermToString[Variable](_.name, vars)
  /*def vartermToString(vars: VarTerm[Variable]): String = vars match {
    case VTUnit => "()"
    case VTSingle(x) => x.name
    case VTCons(VTSingle(x),xs) => x.name + "," + vartermToString(xs)
    case VTCons(VTUnit,xs) => "()," + vartermToString(xs)
    case VTCons(a,b) => s"(${vartermToString(a)},${vartermToString(b)})"
  }*/

  def index1(name:String) : String = name+"1"
  def index2(name:String) : String = name+"2"
  def index(left:Boolean, name:String) : String =
    if (left) index1(name) else index2(name)

/*
  class Indexed(left: Boolean) {
    def unapply(variable: Variable) : Option[Variable] = variable match {
      case Indexed(var2, `left`) => Some(var2)
      case _ => None
    }
  }
*/

  object Indexed {
    def unapply(name: String): Option[(String, Boolean)] = {
      if (name.isEmpty) return None
      def basename = name.substring(0, name.length-1)

      name.last match {
        case '1' => Some((basename, true))
        case '2' => Some((basename, false))
        case _ => None
      }
    }
    def unapply(variable: Variable): Option[(Variable, Boolean)] = variable.name match {
      case Indexed(name, left) => Some(variable.rename(name), left)
      case _ => None
    }
  }

  object IndexedC {
    def unapply(v: CVariable): Option[(CVariable, Boolean)] = {
      if (v.name.isEmpty) return None
      def basename = v.name.substring(0, v.name.length-1)

      v.name.last match {
        case '1' => Some((CVariable(basename, v.valueTyp), true))
        case '2' => Some((CVariable(basename, v.valueTyp), false))
        case _ => None
      }
    }
  }

  def varsNamesToString(vars: Iterable[String]): String =
    if (vars.isEmpty) "∅" else
      vars.mkString(", ")

  def varsToString(vars: Iterable[Variable]): String = vars match {
    case _ : AllSet[_] => "all variables"
    case _ => varsNamesToString(vars.map(_.name))
  }
}

final case class QVariable(name:String, override val valueTyp: Typ) extends Variable {
  override val hash: Hash[QVariable.this.type] =
    HashTag()(RawHash.hashString(name), Hashable.hash(valueTyp))


  override def index1: QVariable = QVariable(Variable.index1(name),valueTyp)
  override def index2: QVariable = QVariable(Variable.index2(name),valueTyp)
  override def index(left:Boolean): QVariable = if (left) index1 else index2
  override val variableName: String = name
  override def toString: String = s"quantum var $name : ${IsabelleX.pretty(valueTyp)}"

  override def isQuantum: Boolean = true
  override def isClassical: Boolean = false

  override def rename(name: String): Variable = copy(name=name)

  override def classicalQuantumWord: String = "quantum"

  override def substitute(renaming: List[(Variable, Variable)]): QVariable =
    super.substitute(renaming).asInstanceOf[QVariable]
}

object QVariable {
  implicit object ordering extends Ordering[QVariable] {
    override def compare(x: QVariable, y: QVariable): Int =
      Variable.ordering.compareSame(x,y)
  }

  def fromTerm_var(context: IsabelleX.ContextX, x: Term): QVariable = x match {
    case Free(name,typ) =>
      QVariable(name, GIsabelle.dest_variableT(typ))
    case _ => throw new java.lang.RuntimeException(f"Cannot transform $x into QVariable")
  }

  def fromQVarList(context: IsabelleX.ContextX, qvs: Term): List[QVariable] = qvs match {
    case Const(GIsabelle.variable_unit.name, _) => Nil
    case App(Const(IsabelleConsts.variable_singleton,_), v) => List(fromTerm_var(context, v))
    case App(App(Const(IsabelleConsts.variable_concat,_), v), vs) =>
      val v2 = fromQVarList(context, v)
      assert(v2.length==1)
      val vs2 = fromQVarList(context, vs)
      v2.head :: vs2
    case _ => throw new RuntimeException("Illformed variable list")
  }




}

final case class CVariable(name:String, override val valueTyp: Typ) extends Variable {
  override val hash: Hash[CVariable.this.type] =
    HashTag()(RawHash.hashString(name), Hashable.hash(valueTyp))

  override def index1: CVariable = CVariable(Variable.index1(name),valueTyp)
  override def index2: CVariable = CVariable(Variable.index2(name),valueTyp)
  override def index(left:Boolean): CVariable = if (left) index1 else index2
//  override def valueTyp: pure.Typ = typ.isabelleTyp
  override val variableName : String= "var_"+name
  def valueTerm(implicit isa: de.unruh.isabelle.control.Isabelle, ec: ExecutionContext): Term = Free(name, valueTyp)

  override def toString: String = s"classical var $name : ${IsabelleX.pretty(valueTyp)}"

  override def isQuantum: Boolean = false
  override def isClassical: Boolean = true

  override def rename(name: String): Variable = copy(name=name)

  override def classicalQuantumWord: String = "classical"

  override def substitute(renaming: List[(Variable, Variable)]): CVariable =
    super.substitute(renaming).asInstanceOf[CVariable]
}

object CVariable {
  implicit object ordering extends Ordering[CVariable] {
    override def compare(x: CVariable, y: CVariable): Int =
      Variable.ordering.compareSame(x,y)
  }

  def fromTerm_var(context: IsabelleX.ContextX, x: Term): CVariable = x match {
    case Free(name,typ) =>
      assert(name.startsWith("var_"))
      CVariable(name.stripPrefix("var_"), GIsabelle.dest_variableT(typ))
    case _ => throw new RuntimeException("Illformed variable term")
  }

  def fromCVarList(context: IsabelleX.ContextX, cvs: Term): List[CVariable] = cvs match {
    case Const(GIsabelle.variable_unit.name, _) => Nil
    case App(Const(IsabelleConsts.variable_singleton,_), v) => List(fromTerm_var(context, v))
    case App(App(Const(IsabelleConsts.variable_concat,_), v), vs) =>
      val v2 = fromCVarList(context, v)
      assert(v2.length==1)
      val vs2 = fromCVarList(context, vs)
      v2.head :: vs2
    case _ => throw new RuntimeException("Illformed variable list")
  }
}


