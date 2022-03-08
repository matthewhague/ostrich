/**
 * This file is part of Ostrich, an SMT solver for strings.
 * Copyright (c) 2022 Matthew Hague, Philipp Ruemmer. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * 
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * 
 * * Neither the name of the authors nor the names of their
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package ostrich

import ap.basetypes.IdealInt
import ap.parameters.Param
import ap.proof.ModelSearchProver
import ap.proof.theoryPlugins.Plugin
import ap.proof.goal.Goal
import ap.terfor.{ConstantTerm, VariableTerm, Formula, Term, TerForConvenience}
import ap.terfor.conjunctions.{Conjunction, ReduceWithConjunction}
import ap.terfor.preds.Atom
import ap.terfor.linearcombination.LinearCombination
import ap.types.Sort

import scala.collection.mutable.{ArrayBuffer, HashMap => MHashMap}

class OstrichNielsenSplitter(goal : Goal,
                             theory : OstrichStringTheory,
                             flags : OFlags) {
  import theory.{_str_++, _str_len, strDatabase, StringSort}
  import OFlags.debug

  val order        = goal.order
  val X            = new ConstantTerm("X")
  val extOrder     = order extend X

  val rand         = Param.RANDOM_DATA_SOURCE(goal.settings)

  val facts        = goal.facts
  val predConj     = facts.predConj
  val concatLits   = predConj.positiveLitsWithPred(_str_++)
  val concatPerRes = concatLits groupBy (_(2))
  val lengthLits   = predConj.positiveLitsWithPred(_str_len)
  val lengthMap    = (for (a <- lengthLits.iterator) yield (a(0), a(1))).toMap

  def resolveConcat(t : LinearCombination)
                  : Option[(LinearCombination, LinearCombination)] =
    for (lits <- concatPerRes get t) yield (lits.head(0), lits.head(1))

  def lengthFor(t : LinearCombination) : LinearCombination =
    if (strDatabase isConcrete t)
      LinearCombination((strDatabase term2ListGet t).size)
    else
      lengthMap(t)

  def eval(t           : LinearCombination,
           lengthModel : ReduceWithConjunction) : Int = {
    import TerForConvenience._
    implicit val o = extOrder

    val f = lengthModel(t === X)
    assert(f.size == 1 && f.constants == Set(X))
    (-f.head.constant).intValueSafe
  }

  def evalLengthFor(t : LinearCombination,
                    lengthModel : ReduceWithConjunction) : Int =
    eval(lengthFor(t), lengthModel)

  type ChooseSplitResult =
    (Seq[LinearCombination], // left terms
     LinearCombination,      // length of concat of left terms
     LinearCombination,      // term to split
     Seq[LinearCombination]) // right terms

  def chooseSplit(splitLit1    : Atom,
                  splitLit2    : Atom,
                  lengthModel  : ReduceWithConjunction)
                               : ChooseSplitResult = {
    val splitLenConc = evalLengthFor(splitLit2(0), lengthModel)
    chooseSplit(splitLit1, splitLenConc, lengthModel,
                List(), List(), List())
  }

  def chooseSplit(t            : LinearCombination,
                  splitLen     : Int,
                  lengthModel  : ReduceWithConjunction,
                  leftTerms    : List[LinearCombination],
                  leftLenTerms : List[LinearCombination],
                  rightTerms   : List[LinearCombination])
                               : ChooseSplitResult =
    (concatPerRes get t) match {
      case Some(lits) =>
        chooseSplit(lits.head, splitLen, lengthModel,
                    leftTerms, leftLenTerms, rightTerms)
      case None =>
        ((leftTerms.reverse,
          LinearCombination.sum(
            for (t <- leftLenTerms) yield (IdealInt.ONE, t), order),
          t, rightTerms))
    }

  def chooseSplit(lit          : Atom,
                  splitLen     : Int,
                  lengthModel  : ReduceWithConjunction,
                  leftTerms    : List[LinearCombination],
                  leftLenTerms : List[LinearCombination],
                  rightTerms   : List[LinearCombination])
                               : ChooseSplitResult = {
    val left        = lit(0)
    val right       = lit(1)
    val leftLen     = lengthFor(left)
    val leftLenConc = eval(leftLen, lengthModel)

    if (splitLen <= leftLenConc)
      chooseSplit(left, splitLen, lengthModel,
                  leftTerms, leftLenTerms, right :: rightTerms)
    else
      chooseSplit(right, splitLen - leftLenConc, lengthModel,
                  left :: leftTerms, leftLen :: leftLenTerms, rightTerms)
  }

  def splittingFormula(split     : ChooseSplitResult,
                       splitLit2 : Atom)
                                 : Conjunction = {
    import TerForConvenience._
    implicit val o = order

    val (leftTerms, _, symToSplit, rightTerms) = split

    val varSorts   = new ArrayBuffer[Sort]
    val matrixFors = new ArrayBuffer[Formula]
    val varLengths = new MHashMap[VariableTerm, Term]

    def newVar(s : Sort) : VariableTerm = {
      val res = VariableTerm(varSorts.size)
      varSorts += s
      res
    }

    def lengthFor2(t : Term) : Term =
      t match {
        case t : VariableTerm =>
          varLengths.getOrElseUpdate(t, {
            val len = newVar(Sort.Integer)
            matrixFors += _str_len(List(l(t), l(len)))
            matrixFors += l(len) >= 0
            len
          })
        case _ =>
          lengthFor(t)
      }

    def addConcat(left : Term, right : Term, res : Term) : Unit = {
      matrixFors += _str_++ (List(l(left), l(right), l(res)))
      matrixFors += lengthFor2(left) + lengthFor2(right) === lengthFor2(res)
    }

    def addConcatN(terms : Seq[Term], res : Term) : Unit =
      terms match {
        case Seq(t) =>
          matrixFors += t === res
        case terms => {
          assert(terms.size > 1)
          val prefixes =
            (for (_ <- (2 until terms.size).iterator)
             yield newVar(StringSort)) ++ Iterator(res)
          terms reduceLeft[Term] {
            case (t1, t2) => {
              val s = prefixes.next
              addConcat(t1, t2, s)
              s
            }
          }
        }
      }

    val leftSplitSym, rightSplitSym = newVar(StringSort)

    addConcat(leftSplitSym, rightSplitSym, symToSplit)

    addConcatN(leftTerms ++ List(leftSplitSym),   splitLit2(0))
    addConcatN(List(rightSplitSym) ++ rightTerms, splitLit2(1))

    existsSorted(varSorts.toSeq, conj(matrixFors))
  }

  def diffLengthFormula(split : ChooseSplitResult,
                        splitLit2 : Atom) : Conjunction = {
    import TerForConvenience._
    implicit val o = order

    val (_, leftTermsLen, symToSplit, _) = split
    val splitLen = lengthFor(splitLit2(0))

    (splitLen < leftTermsLen) |
    (splitLen > leftTermsLen + lengthFor(symToSplit))
  }

  type DecompPoint =
    (Atom,              // Atom containing the terms
     Seq[Term],         // left terms, in reverse order
     LinearCombination, // cumulative length of the left terms
     Seq[Term])         // right terms

  def decompPoints(lit : Atom) : Seq[DecompPoint] = {
    implicit val o = order

    val points = new ArrayBuffer[DecompPoint]

    def genPoints(t          : LinearCombination,
                  leftTerms  : List[Term],
                  len        : LinearCombination,
                  rightTerms : List[Term]) : Unit =
      if (strDatabase isConcrete t) {

      } else {
        (concatPerRes get t) match {
          case Some(Seq(concatLit)) => {
            genPoints(concatLit(0),
                      leftTerms,
                      len,
                      concatLit(1) :: rightTerms)
            genPoints(concatLit(1),
                      concatLit(0) :: leftTerms,
                      len + lengthFor(concatLit(0)),
                      rightTerms)
          }
          case _ =>
            points += ((lit, leftTerms, len, rightTerms))
        }
      }

    genPoints(lit(0), List(),       LinearCombination.ZERO, List(lit(1)))
    genPoints(lit(1), List(lit(0)), lengthFor(lit(0)),      List())

    points.toSeq
  }

  def decomposeHelp(lits : Seq[Atom]) : Seq[Plugin.Action] = {
    val resultTerm  = lits.head(0)
    val splitPoints = new MHashMap[Term, DecompPoint]

    
    List()
  }

  /**
   * Decompose equations of the form a.b = c.d if it can be derived
   * that |a| = |c|.
   */
  def decomEquation : Seq[Plugin.Action] = {
    val multiGroups =
      concatPerRes filter {
        case (res, lits) => lits.size >= 2 || (strDatabase isConcrete res)
      }

    val decompActions =
      for ((res, lits) <- multiGroups) yield {

      }

    List()
  }

  /**
   * Decompose equations of the form a.b = w, in which w is some
   * concrete word.
   */
  def decomSimpleEquations : Seq[Plugin.Action] = {
    for (lit <- concatLits;
         if strDatabase isConcrete lit.last) yield ()

    List()
  }

  /**
   * Decompose one equation of the form a.b = w, in which w is some
   * concrete word.
   */
  def decomSimpleEquation(lit : Atom) : Seq[Plugin.Action] = {


    List()
  }

  /**
   * Apply the Nielsen transformation to some selected equation.
   * 
   * TODO: handle also the case where we don't have length information
   * available.
   */
  def splitEquation : Seq[Plugin.Action] = {
    val multiGroups =
      concatPerRes filter {
        case (res, lits) => lits.size >= 2 && !(strDatabase isConcrete res)
      }

    val splittableTerms =
      concatLits.iterator.map(_(2)).filter(multiGroups.keySet).toList.distinct

    if (splittableTerms.isEmpty)
      return List()

    val termToSplit = splittableTerms(rand nextInt splittableTerms.size)
    val literals    = multiGroups(termToSplit)

    val splitLit1   = literals(rand nextInt literals.size)
    val splitLit2   = (literals filterNot (_ == splitLit1))(
                        rand nextInt (literals.size - 1))

    val lengthModel =
      ModelSearchProver(Conjunction.negate(facts.arithConj, order), order)

    if (lengthModel.isFalse)
      return List(Plugin.AddAxiom(List(facts.arithConj), Conjunction.FALSE, theory))

    val lengthRed =
      ReduceWithConjunction(lengthModel, extOrder)

    val zeroSyms = for (t <- (splitLit2 take 2).iterator;
                        if evalLengthFor(t, lengthRed) == 0)
                   yield t

    if (zeroSyms.hasNext) {
      val zeroSym = zeroSyms.next
      Console.err.println("Assuming " + zeroSym + " = \"\"")

      import TerForConvenience._
      implicit val o = order

      List(
        Plugin.AxiomSplit(List(),
                          List((zeroSym === strDatabase.str2Id(""), List()),
                               (lengthFor(zeroSym) > 0,             List())),
                          theory)
      )
    } else {
      val split    = chooseSplit(splitLit1, splitLit2, lengthRed)
      val splitSym = split._3

      Console.err.println(
        "Applying Nielsen transformation (# word equations: " + multiGroups.size +
          "), splitting " + splitSym)
      Console.err.println("  " +
                            term2String(splitLit1(0)) + " . " +
                            term2String(splitLit1(1)) + " == " +
                            term2String(splitLit2(0)) + " . " +
                            term2String(splitLit2(1)))

      val f1 = splittingFormula(split, splitLit2)
      val f2 = diffLengthFormula(split, splitLit2)

      List(
        Plugin.AxiomSplit(concatLits ++ lengthLits, // TODO: make specific
                          List((f1,
                                List(Plugin.RemoveFacts(
                                       Conjunction.conj(splitLit2, order)))),
                               (f2, List())),
                          theory)
      )
    }

  }

  private def term2String(t : Term) =
    (strDatabase term2Str t) match {
      case Some(str) => "\"" + str + "\""
      case None => t.toString
    }

}