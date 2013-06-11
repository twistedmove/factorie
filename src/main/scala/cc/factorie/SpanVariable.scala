/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, ListBuffer, FlatHashTable,DoubleLinkedList}
import scala.reflect.Manifest
import scala.util.Random
import scala.util.Sorting

/** A subsequence of a Chain, and the value of a SpanVar.
    @author Andrew McCallum */
trait SpanValue[C<:Chain[C,E],E<:ChainLink[E,C]] extends IndexedSeq[E] {
  def apply(i:Int) = chain.links(start + i)
  def chain: C
  def start: Int
  def length: Int
  override def head: E = apply(0)
  def hasSuccessor(i: Int) = (start + length - 1 + i) < chain.length
  def hasPredecessor(i: Int) = (start - i) >= 0
  def successor(i: Int): E = if (hasSuccessor(i)) chain(start + length - 1 + i) else null.asInstanceOf[E]
  def predecessor(i: Int): E = if (hasPredecessor(i)) chain(start - i) else null.asInstanceOf[E]
}

/** A Span is a subsequence of ChainLink elements within a Chain.
    This trait does not inherit from Var, but SpanVar does.
    @see cc.factorie.app.nlp.TokenSpan
    @author Andrew McCallum */   
trait Span[This<:Span[This,C,E],C<:ChainWithSpans[C,This,E],E<:ChainLink[E,C]] extends ThisType[This] with IndexedSeqSimilar[E] {
  this: This =>
  protected var _start = 0
  protected var _length = 0
  var _chain: C = null.asInstanceOf[C] // This var is set automatically in ChainWithSpans.+= and -=
  var _present = true // Indicating if this span should be considered to be in effect; used in diffs representing temporary deletion.
  /** True if this span is currently present in a ChainWithSpans.  Used by Diff objects to handle deleted spans. */
  def present = _present
  /** The position within the Chain at which this Span starts. */
  def start: Int = _start
  /** The number of elements in this Span. */
  override def length: Int = _length
  /** The position within the Chain at which this Span is over.  The last element of this Span is at 'end-1'. */
  def end = start + length - 1
  // TODO Have this create and return a new immutable SpanValue
  /** The current start/length of this Span as a SpanValue. */
  val value: SpanValue[C,E] = new SpanValue[C,E] {
    def chain: C = _chain
    def start = _start
    def length = _length
  }
  /** The Chain of which this Span is a subsequence. */
  def chain: C = _chain
  def hasSuccessor(i: Int): Boolean = (start + length - 1 + i) < chain.length
  def hasPredecessor(i: Int): Boolean = (start - i) >= 0
  def successor(i: Int): E = if (hasSuccessor(i)) chain(start + length - 1 + i) else null.asInstanceOf[E]
  def predecessor(i: Int): E = if (hasPredecessor(i)) chain(start - i) else null.asInstanceOf[E]
  override def apply(i: Int): E = _chain(i + _start)
  // Other Seq-related methods, such as "head" and "iterator" are provided by IndexedSeqVar inherited in SpanVar.
  def isAtStart = start == 0
  def overlaps(that: Span[_,_<:AnyRef,_<:AnyRef]): Boolean = {
    assert(this.chain eq that.chain)
    (that.start <= this.start && that.end >= this.start) ||
    (this.start <= that.start && this.end >= that.start)
  }
  def isAtEnd: Boolean = start + length == chain.length
  def prevWindow(n:Int): Seq[E] = for (i <- math.max(0,start-n) until start) yield chain(i)
  def nextWindow(n:Int): Seq[E] = for (i <- end+1 until math.min(chain.length-1,end+n)) yield chain(i)
  def window(n:Int): Seq[E] = for (i <- math.max(0,start-n) to math.min(chain.length-1,end+n)) yield chain(i)
  def windowWithoutSelf(n:Int): Seq[E] = for (i <- math.max(0,start-n) to math.min(chain.length-1,end+n); if (i < start || i > end)) yield chain(i)
  // Support for next/prev of elements within a span
  @inline private def checkInSpan(elt:E): Unit = { require(elt.chain eq chain); require(elt.position >= start && elt.position <= end) } 
  def hasNext(elt:E): Boolean = { checkInSpan(elt); elt.position < end }
  def hasPrev(elt:E): Boolean = { checkInSpan(elt); elt.position > start }
  def next(elt:E): E = if (hasNext(elt)) elt.next else null.asInstanceOf[E]
  def prev(elt:E): E = if (hasPrev(elt)) elt.prev else null.asInstanceOf[E]
}

/** An abstract variable whose value is a subsequence of a Chain.
    These are used, for example, as a superclass of TokenSpan, representing a sequence of Tokens within a Document.
    @author Andrew McCallum */
trait SpanVar[This<:SpanVar[This,C,E],C<:ChainWithSpansVar[C,This,E],E<:ChainLink[E,C]] extends Span[This,C,E] with IndexedSeqVar[E] /*with VarAndValueGenericDomain[SpanVar[This,C,E],SpanValue[C,E]]*/ with VarWithValue[SpanValue[C,E]] {
  this: This =>
  /** If true, this SpanVariable will be scored by a difflist, even if it is in its deleted non-"present" state. */
  def diffIfNotPresent = false
  def preChange(implicit d:DiffList): Unit = {}
  def postChange(implicit d:DiffList): Unit = {}
  def delete(implicit d: DiffList): Unit = { preChange; chain.removeSpan(this)(d); postChange }
  def setLength(l: Int)(implicit d: DiffList): Unit = if (l != length) { preChange; new SetLength(_length, l); postChange }
  def trimStart(n: Int)(implicit d: DiffList): Unit = if (n >= length) this.delete else if (n > 0) { preChange; new TrimStart(n); postChange }
  def trimEnd(n: Int)(implicit d: DiffList): Unit = if (n >= length) this.delete else if (n > 0) { preChange; new TrimEnd(n); postChange }
  def prepend(n: Int)(implicit d: DiffList): Unit = if (n > 0) { preChange; new Prepend(n); postChange }
  def append(n: Int)(implicit d: DiffList): Unit = if (n > 0) { preChange; new Append(n); postChange }
  def canPrepend(n: Int) = _start >= n
  def canAppend(n: Int) = _start + _length + n <= chain.length
  /** This should be called in the constructor */
  // Why do I need this and also the AddSpan Diff??
  /*case class NewSpan(implicit d: DiffList) extends Diff {
    // NewSpan cannot be an AutoDiff because of initialization ordering, done will end up false. 
    // TODO But I should get rid of 'done' and just use 'present' instead.
    //println("NewSpanVariable d.length="+d.length)
    var done = false
    if (d != null) d += this
    redo
    def variable = {if (done || diffIfNotPresent) SpanVar.this else null}
    def redo = {assert(!done); done = true; assert(present) }
    def undo = {assert(done); done = false; assert(!present) }
    override def toString = "NewSpan("+SpanVar.this+")"
  }*/
  /*@deprecated("Remove") case class DeleteSpanVariable(implicit d: DiffList) extends Diff {
    // cannot be AutoDiff for same reasons as NewSpanVariable
    var done = false
    if (d != null) d += this
    redo
    def variable: SpanInChainVar[T] = if (done && !diffIfNotPresent) null else SpanInChainVar.this
    def redo = { assert(!done); done = true; assert(!present) }
    def undo = { assert(done); done = false; assert(present) }
    override def toString = "DeleteSpanVariable("+SpanInChainVar.this+")"
  }*/
  case class SetStart(oldStart: Int, newStart: Int)(implicit d: DiffList) extends AutoDiff {
    def variable = if (present || diffIfNotPresent) SpanVar.this else null
    def redo = _start = newStart
    def undo = _start = oldStart
  }
  case class SetLength(oldLength: Int, newLength: Int)(implicit d: DiffList) extends AutoDiff {
    def variable = if (present || diffIfNotPresent) SpanVar.this else null
    def redo = _length = newLength
    def undo = _length = oldLength
  }
  case class TrimStart(n: Int)(implicit d: DiffList) extends AutoDiff {
    def variable = if (present || diffIfNotPresent) SpanVar.this else null
    def redo = {assert(n < _length); _start += n; _length -= n}
    def undo = {assert(_start - n >= 0); _start -= n; _length += n}
    override def toString = "TrimStart("+n+","+SpanVar.this+")"
  }
  case class TrimEnd(n: Int)(implicit d: DiffList) extends AutoDiff {
    def variable = if (present || diffIfNotPresent) SpanVar.this else null
    def redo = {assert(n < _length); _length -= n}
    def undo = _length += n
    override def toString = "TrimEnd("+n+","+SpanVar.this+")"
  }
  case class Prepend(n: Int)(implicit d: DiffList) extends AutoDiff {
    def variable = if (present || diffIfNotPresent) SpanVar.this else null
    def redo = {assert(canPrepend(n)); _start -= n; _length += n}
    def undo = {_start += n; _length -= n}
    override def toString = "Prepend("+n+","+SpanVar.this+")"
  }
  case class Append(n: Int)(implicit d: DiffList) extends AutoDiff {
    //if (!canAppend(n)) { println("Append n="+n+" start="+variable.start+" length="+variable.length+" parent.length="+variable.parent.length) }
    def variable = if (present || diffIfNotPresent) SpanVar.this else null
    def redo = {assert(canAppend(n)); _length += n}
    def undo = _length -= n
    override def toString = "Append("+n+","+SpanVar.this+")"
  }  
}

/** A variable whose value is a subsequence of a Chain.
    These are used, for example, as a superclass of TokenSpan, representing a sequence of Tokens within a Document.
    @author Andrew McCallum */
class SpanVariable[This<:SpanVar[This,C,E],C<:ChainWithSpansVar[C,This,E],E<:ChainLink[E,C]](theChain:C, initialStart:Int, initialLength:Int)(implicit d:DiffList = null) extends SpanVar[This,C,E] {
  this: This =>
  _start = initialStart
  _length = initialLength
  _chain = theChain //null.asInstanceOf[C] // It will get set in _chain.addSpan below.
  _chain.addSpan(this)(d)  // TODO Remove this.  There can be a span that the document doesn't know about.  But exactly when would it get registered in the ChainWithSpans? -akm
  //if (d ne null) NewSpan // Add NewSpan diff to the DiffList
}

/** A Chain that maintains a list of Spans within it.
    It provides various methods for querying the set of Spans by position, relative position, overlaps, etc. 
    @author Andrew McCallum */
trait ChainWithSpans[This<:ChainWithSpans[This,S,E],S<:Span[S,This,E],E<:ChainLink[E,This]] extends Chain[This,E] {
  this: This =>
  private val _spans = new scala.collection.mutable.ListBuffer[S];
  def spans: Seq[S] = _spans
  def spansOfClass[A<:S](c:Class[A]): Seq[A] = _spans.filter(s => c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def spansOfClass[A<:S](implicit m:Manifest[A]): Seq[A] = spansOfClass[A](m.erasure.asInstanceOf[Class[A]])
  def +=(s:S): Unit = { require((s._chain eq this) || (s._chain eq null)); _spans.prepend(s); s._chain = this; s._present = true }
  def -=(s:S): Unit = { _spans -= s; s._present = false }
  // Spans sorted by their start position
  def orderedSpans: Seq[S] = spans.toList.sortWith((s1,s2) => s1.start < s2.start) // TODO Make this more efficient by avoiding toList
  def orderedSpansOfClass[A<:S](c:Class[A]): Seq[A] = spansOfClass(c).toList.sortWith((s1,s2) => s1.start < s2.start) // TODO Make this more efficient by avoiding toList
  def orderedSpansOfClass[A<:S](implicit m:Manifest[A]): Seq[A] = orderedSpansOfClass(m.erasure.asInstanceOf[Class[A]])
  // Spans the cover a position
  def hasSpanContaining(position:Int): Boolean = spans.exists(s => s.start <= position && position < (s.start + s.length))
  def hasSpanOfClassContaining[A<:S](c:Class[A], position:Int): Boolean = spans.exists(s => s.start <= position && position < (s.start + s.length) && c.isAssignableFrom(s.getClass))
  def hasSpanOfClassContaining[A<:S](position:Int)(implicit m:Manifest[A]): Boolean = hasSpanOfClassContaining(m.erasure.asInstanceOf[Class[A]], position)
  def spansContaining(position: Int): Seq[S] = spans.filter(s => s.start <= position && position < (s.start + s.length))
  def spansOfClassContaining[A<:S](c:Class[A], position: Int): Seq[A] = spans.filter(s => s.start <= position && position < (s.start + s.length) && c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def spansOfClassContaining[A<:S](position: Int)(implicit m:Manifest[A]): Seq[A] = spansOfClassContaining(m.erasure.asInstanceOf[Class[A]], position)
  // Spans that start exactly at position
  def spansStartingAt(position: Int): Seq[S] = spans.filter(s => s.start == position)
  def spansOfClassStartingAt[A<:S](c:Class[A], position: Int)(implicit m:Manifest[A]): Seq[A] = spans.filter(s => s.start == position && c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def spansOfClassStartingAt[A<:S](position: Int)(implicit m:Manifest[A]): Seq[A] = spansOfClassStartingAt(m.erasure.asInstanceOf[Class[A]], position)
  // Spans that end exactly at position
  def spansEndingAt(position: Int): Seq[S] = spans.filter(s => s.start + s.length - 1 == position)
  def spansOfClassEndingAt[A<:S](c:Class[A], position: Int)(implicit m:Manifest[A]): Seq[A] = spans.filter(s => s.start + s.length - 1 == position && c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def spansOfClassEndingAt[A<:S](position: Int)(implicit m:Manifest[A]): Seq[A] = spansOfClassEndingAt(m.erasure.asInstanceOf[Class[A]], position) 
  // Closest span starting prior to position
  def spanPreceeding(position:Int): S = {
    var result = null.asInstanceOf[S]
    for (s <- _spans) if (s.start < position && ((result eq null) || (s.start > result.start))) result = s
    result
  } 
  def spansPreceeding(position:Int): Seq[S] = _spans.filter(s => s.start < position)
  def spanOfClassPreceeding[A<:S](position:Int)(implicit m:Manifest[A]): A = {
    var result = null.asInstanceOf[A]
    val mc = m.erasure
    for (s <- _spans) if (s.start < position && mc.isAssignableFrom(s.getClass) && ((result eq null) || (s.start > result.start))) result = s.asInstanceOf[A]
    result
  } 
  def spansOfClassPreceeding[A<:S](position:Int)(implicit m:Manifest[A]): Seq[A] = {
    val mc = m.erasure
    _spans.filter(s => s.start < position && mc.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  }
  // Closest span starting after to position
  def spanFollowing(position:Int): S = {
    var result = null.asInstanceOf[S]
    for (s <- _spans) if (s.start > position && ((result eq null) || (s.start < result.start))) result = s
    result
  }
  def spansFollowing(position:Int): Seq[S] = _spans.filter(s => s.start > position)
  def spanOfClassFollowing[A<:S](position:Int)(implicit m:Manifest[A]): S = {
    var result = null.asInstanceOf[A]
    val mc = m.erasure
    for (s <- _spans) if (s.start > position && mc.isAssignableFrom(s.getClass) && ((result eq null) || (s.start < result.start))) result = s.asInstanceOf[A]
    result
  } 
  def spansOfClassFollowing[A<:S](position:Int)(implicit m:Manifest[A]): Seq[A] = {
    val mc = m.erasure
    _spans.filter(s => s.start > position && mc.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  }
}

/** A ChainVar that maintains a list of Spans within it.
    It provides various methods for querying the set of Spans by position, relative position, overlaps, etc. 
    @author Andrew McCallum */
trait ChainWithSpansVar[This<:ChainWithSpansVar[This,S,E],S<:SpanVar[S,This,E],E<:ChainLink[E,This]] extends ChainVar[This,E] with ChainWithSpans[This,S,E] with IndexedSeqVar[E] /*with VarAndElementType[ChainWithSpansVar[This,S,E],E]*/ {
  this: This =>
  /** Add the span to the list of spans maintained by this VariableSeqWithSpans.
      Typically you would not call this yourself; it is called automatically from the SpanVariable constructor. */
  def addSpan(s:S)(implicit d:DiffList): Unit = {
    // This check is now done in ChainWithSpans.+=
    //require(s.chain == null, "VariableSeqWithSpans.addSpan: span.chain="+s.chain+" already belongs to another Chain. equal="+(s.chain eq this)) // This check was commented out before 23 May 2013 -akm
    AddSpanVariable(s)
  }
  /** Remove the span from the list of spans maintained by this ChainWithSpans.
      Typically you would not call this yourself; it is called automatically from SpanVariable.delete. */
  def removeSpan(s:S)(implicit d:DiffList): Unit = {
    require(s.chain == this)
    RemoveSpanVariable(s)
  }
  def clearSpans(implicit d:DiffList): Unit = {
    // Make a copy of the collection of spans so its iterator doesn't get confused as we delete them
    spans.toList.foreach(removeSpan(_)(d))
  }
  case class AddSpanVariable(span:S)(implicit d: DiffList) extends Diff {
    // Cannot be an AutoDiff, because of initialization ordering 'done' will end up false
    var done = false
    if (d != null) d += this
    redo
    def variable: S = if (span._present || span.diffIfNotPresent) span else null.asInstanceOf[S]
    def redo = { ChainWithSpansVar.this.+=(span); assert(!done); done = true }
    def undo = { /*println("AddSpan.undo1 "+spans);*/ ChainWithSpansVar.this.-=(span); /*println("AddSpan.undo2 "+spans);*/ assert(done); done = false }
    override def toString = "AddSpanVariable("+span+")"
  }
  case class RemoveSpanVariable(span:S)(implicit d: DiffList) extends Diff {
    // Cannot be an AutoDiff, because of initialization ordering 'done' will end up false
    var done = false
    if (d != null) d += this
    redo
    def variable: S = if (span._present || span.diffIfNotPresent) span else null.asInstanceOf[S]
    def redo = { ChainWithSpansVar.this.-=(span); assert(!done); done = true }
    def undo = { ChainWithSpansVar.this.+=(span); assert(done); done = false }
    override def toString = "RemoveSpanVariable("+span+")"
  }
}
