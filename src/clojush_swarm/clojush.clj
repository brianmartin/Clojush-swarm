;; clojush.clj
;;
;; This file implements a version of the Push programming language and the PushGP genetic
;; programming system in the Clojure programming language. See the accompanying README
;; file for usage instructions and other notes.
;;
;; Copyright (c) 2010 Lee Spector (lspector@hampshire.edu)
;;
;; This program is free software: you can redistribute it and/or modify it under
;; the terms of version 3 of the GNU General Public License as published by the
;; Free Software Foundation, available from http://www.gnu.org/licenses/gpl.txt.
;;
;; This program is distributed in the hope that it will be useful, but WITHOUT ANY
;; WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
;; PARTICULAR PURPOSE. See the GNU General Public License (http://www.gnu.org/licenses/)
;; for more details.

;;;;;
;; swarmiji logs configuration

(in-ns 'org.rathore.amit.utils.config)
(def *clj-utils-config*
  {:log-to-console true
   :logs-dir "/home/brian/src/swarm/clojush-swarm/logs"
   :log-filename-prefix "clojush"
   :exception-notifier {:enabled false}})

;;;;;
;; namespace declaration and access to needed libraries
(ns clojush-swarm.clojush
  (:require [clojure.zip :as zip] 
	    [clojure.contrib.math :as math]
	    [clojure.contrib.seq-utils :as seq-utils]
	    [clojure.walk :as walk]
            [org.runa.swarmiji.sevak.sevak-core :as sevak]
            [org.runa.swarmiji.client.client-core :as client]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; globals

(def push-types '(:exec :integer :float :code :boolean :auxiliary))
(def max-number-magnitude 1000000000000)
(def min-number-magnitude 1.0E-10)
(def top-level-push-code true)
(def top-level-pop-code true)
(def evalpush-limit 150)
(def evalpush-time-limit 10000000) ;; in nanoseconds
(def min-random-integer -10)
(def max-random-integer 10)
(def min-random-float -1.0)
(def max-random-float 1.0)
(def max-points-in-random-expressions 50) ;; for code_rand
(def maintain-histories true) ;; histories are lists of total-error values for ancestors
(def maintain-ancestors false) ;; if true save all ancestors in each individual (costly)
(def print-ancestors-of-solution false)

;; The following globals require values because they are used in Push instructions but they
;; may be reset by arguments to pushgp or other systems that use Push.
(def global-atom-generators ()) ;; the defalult for this will be set below
(def global-max-points-in-program 100)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; random code generator

(def thread-local-random-generator (new java.util.Random))

(defn lrand-int
  "Return a random integer, using the thread-local random generator, that is less than the
provided n. Arguments greater than 2^31-1 are treated as if they were 2^31-1 (2147483647)."
  [n]
  (if (<= n 1)
    0
    (if (= (type n) java.lang.Integer)
        (. thread-local-random-generator (nextInt n))
	(. thread-local-random-generator (nextInt 2147483647))))) ;; biggest java.lang.Integer

(defn lrand
  "Return a random float between 0 and 1 usng the thread-local random generator."
  ([] (. thread-local-random-generator (nextFloat)))
  ([n] (* n (lrand))))


(defn decompose
  "Returns a list of at most max-parts numbers that sum to number.
The order of the numbers is not random (you may want to shuffle it)."
  [number max-parts]
  (if (or (<= max-parts 1) (<= number 1))
    (list number)
    (let [this-part (inc (lrand-int (dec number)))]
      (cons this-part (decompose (- number this-part)
				 (dec max-parts))))))

(defn shuffle
  [lst]
  (if (empty? (rest lst))
    lst
    (let [index (lrand-int (count lst))
	  item (nth lst index)
	  remainder (concat (subvec (into [] lst) 0 index)
			    (subvec (into [] lst) (inc index)))]
      (cons item (shuffle remainder)))))

(defn random-element
  [lst]
  (nth lst (lrand-int (count lst))))

(defn random-code-with-size
  "Returns a random expression containing the given number of points."
  [points atom-generators]
  (if (< points 2)
    (let [element (random-element atom-generators)]
      (if (fn? element)
	(element)
	element))
    (let [elements-this-level 
	  (shuffle (decompose (dec points) (dec points)))]
      (doall (map (fn [size] (random-code-with-size size atom-generators))
		  elements-this-level)))))

(defn random-code 
  "Returns a random expression with size limited by max-points."
  [max-points atom-generators]
  (random-code-with-size (inc (lrand-int max-points)) atom-generators))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; utilities

(defn ensure-list [thing] ;; really make-list-if-not-seq, but close enough for here
  (if (seq? thing)
    thing
    (list thing)))

(defn print-return 
  "Prints the provided thing and returns it."
  [thing]
  (println thing)
  thing)

(defn keep-number-reasonable
  "Returns a version of n that obeys limit parameters."
  [n]
  (if (integer? n)
    (cond (> n max-number-magnitude) max-number-magnitude
	  (< n (- max-number-magnitude)) (- max-number-magnitude)
	  true n)
    (cond (> n max-number-magnitude) (* 1.0 max-number-magnitude)
	  (< n (- max-number-magnitude)) (* 1.0 (- max-number-magnitude))
	  (and (< n min-number-magnitude) (> n (- min-number-magnitude))) 0.0
	  true n)))

(defn count-points 
  "Returns the number of points in tree, where each atom and each pair of parentheses 
counts as a point."
  [tree]
  (if (seq? tree)
    (inc (apply + (map count-points tree)))
    1))

(defn code-at-point 
  "Returns a subtree of tree indexed by point-index in a depth first traversal."
  [tree point-index]
  (let [index (mod (math/abs point-index) (count-points tree))
	zipper (zip/seq-zip tree)]
    (loop [z zipper i index]
      (if (zero? i)
	(zip/node z)
	(recur (zip/next z) (dec i))))))

(defn insert-code-at-point 
  "Returns a copy of tree with the subtree formerly indexed by
point-index (in a depth-first traversal) replaced by new-subtree."
  [tree point-index new-subtree]
  (let [index (mod (math/abs point-index) (count-points tree))
	zipper (zip/seq-zip tree)]
    (loop [z zipper i index]
      (if (zero? i)
	(zip/root (zip/replace z new-subtree))
	(recur (zip/next z) (dec i))))))

(defn remove-code-at-point 
  "Returns a copy of tree with the subtree formerly indexed by
point-index (in a depth-first traversal) removed. If removal would
result in an empty list then it is not performed. (NOTE: this is different
from the behavior in other implementations of Push.)"
  [tree point-index]
  (let [index (mod (math/abs point-index) (count-points tree))
	zipper (zip/seq-zip tree)]
    (if (zero? index)
      tree ;; can't remove entire tree
      (loop [z zipper i index]
	(if (zero? i)
	  (zip/root (zip/remove z))
	  (if (and (= i 1) ;; can't remove only item from list
		   (seq? (zip/node z))
		   (= 1 (count (zip/node z))))
	    (zip/root z) ;(zip/remove z))
	    (recur (zip/next z) (dec i))))))))
  
(defn truncate
  "Returns a truncated integer version of n."
  [n]
  (if (< n 0)
    (math/round (math/ceil n))
    (math/round (math/floor n))))

(defn subst
  "Returns the given list but with all instances of that (at any depth)                                   
replaced with this. Read as 'subst this for that in list'. "
  [this that lst]
  (walk/postwalk-replace {that this} lst))

(defn contains-subtree 
  "Returns true if tree contains subtree at any level. Inefficient but
functional implementation."
  [tree subtree]
  (or (= tree subtree)
      (not (= tree (subst (gensym) subtree tree)))))

(defn containing-subtree
  "If tree contains subtree at any level then this returns the smallest
subtree of tree that contains but is not equal to the first instance of
subtree. For example, (contining-subtree '(b (c (a)) (d (a))) '(a)) => (c (a)).
Returns nil if tree does not contain subtree."
  [tree subtree]
  (cond (not (seq? tree)) nil
	(empty? tree) nil
	(some #{subtree} tree) tree
	:else (some (fn [smaller-tree]
		      (containing-subtree smaller-tree subtree))
		    tree)))

(defn all-items
  "Returns a list of all of the items in lst, where sublists and atoms all
count as items. Will contain duplicates if there are duplicates in lst.
Recursion in implementation could be improved."
  [lst]
  (cons lst (if (seq? lst)
	      (apply concat (doall (map all-items lst)))
	      ())))

(defn discrepancy
  "Returns a measure of the discrepancy between list1 and list2. This will
be zero if list1 and list2 are equal, and will be higher the 'more different'
list1 is from list2. The calculation is equivalent to the following:
1. Construct a list of all of the unique items in both of the lists. Sublists 
   and atoms all count as items.                               
2. Initialize the result to zero.
3. For each unique item increment the result by the difference between the
   number of occurrences of the item in list1 and the number of occurrences
   of the item in list2.
4. Return the result."
  [list1 list2]
  (reduce + (vals (merge-with (comp math/abs -)
			      (seq-utils/frequencies (all-items list1))
			      (seq-utils/frequencies (all-items list2))))))

(defn not-lazy
  "Returns lst if it is not a list, or a non-lazy version of lst if it is."
  [lst]
  (if (seq? lst)
    (apply list lst)
    lst))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; states, stacks, and instructions

(defmacro define-push-state-structure []
  `(defstruct push-state ~@push-types))

(define-push-state-structure)

(defn make-push-state
  "Returns an empty push state."
  []
  (struct-map push-state))

(def registered-instructions '())

(defn register-instruction 
  "Add the provided name to the global list of registered instructions."
  [name]
  (def registered-instructions (cons name registered-instructions)))

(def instruction-table (hash-map))

(defmacro define-registered
  [instruction definition]
  `(do (def ~instruction ~definition)
       (register-instruction '~instruction)
       (def instruction-table (assoc instruction-table '~instruction ~definition))))

(defn state-pretty-print
  [state]
  (doseq [t push-types]
    (printf "%s = " t)
    (println (t state))
    (flush)))

(defn push-item
  "Returns a copy of the state with the value pushed on the named stack. This is a utility,
not for use in Push programs."
  [value type state]
  (assoc state type (cons value (type state))))

(defn top-item
  "Returns the top item of the type stack in state. Returns :no-stack-item if called on 
an empty stack. This is a utility, not for use as an instruction in Push programs."
  [type state]
  (let [stack (type state)]
      (if (empty? stack)
          :no-stack-item
          (first stack))))

(defn stack-ref
  "Returns the indicated item of the type stack in state. Returns :no-stack-item if called 
on an empty stack. This is a utility, not for use as an instruction in Push programs.
NOT SAFE for invalid positions."
  [type position state]
  (let [stack (type state)]
    (if (empty? stack)
      :no-stack-item
      (nth stack position))))

(defn pop-item
  "Returns a copy of the state with the specified stack popped. This is a utility,
not for use as an instruction in Push programs."
  [type state]
  (assoc state type (rest (type state))))

(defn registered-for-type
  "Returns a list of all registered instructions with the given type name as a prefix."
  [type]
  (filter #(.startsWith (name %) (name type)) registered-instructions))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ACTUAL INSTRUCTIONS

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; instructions for all types (except auxiliary)

(defn popper 
  "Returns a function that takes a state and pops the appropriate stack of the state."
  [type]
  (fn [state] (pop-item type state)))

(define-registered exec_pop (popper :exec))
(define-registered integer_pop (popper :integer))
(define-registered float_pop (popper :float))
(define-registered code_pop (popper :code))
(define-registered boolean_pop (popper :boolean))

(defn duper 
  "Returns a function that takes a state and duplicates the top item of the appropriate 
stack of the state."
  [type]
  (fn [state]
    (if (empty? (type state))
      state
      (push-item (top-item type state) type state))))

(define-registered exec_dup (duper :exec))
(define-registered integer_dup (duper :integer))
(define-registered float_dup (duper :float))
(define-registered code_dup (duper :code))
(define-registered boolean_dup (duper :boolean))

(defn swapper 
  "Returns a function that takes a state and swaps the top 2 items of the appropriate 
stack of the state."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first-item (stack-ref type 0 state)
	    second-item (stack-ref type 1 state)]
	(->> (pop-item type state) 
	     (pop-item type)
	     (push-item first-item type)
	     (push-item second-item type)))
      state)))

(define-registered exec_swap (swapper :exec))
(define-registered integer_swap (swapper :integer))
(define-registered float_swap (swapper :float))
(define-registered code_swap (swapper :code))
(define-registered boolean_swap (swapper :boolean))

(defn rotter 
  "Returns a function that takes a state and rotates the top 3 items of the appropriate 
stack of the state."
  [type]
  (fn [state]
    (if (not (empty? (rest (rest (type state)))))
      (let [first (stack-ref type 0 state)
	    second (stack-ref type 1 state)
	    third (stack-ref type 2 state)]
	(->> (pop-item type state)
	     (pop-item type)
	     (pop-item type)
	     (push-item second type)
	     (push-item first type)
	     (push-item third type)))
      state)))

(define-registered exec_rot (rotter :exec))
(define-registered integer_rot (rotter :integer))
(define-registered float_rot (rotter :float))
(define-registered code_rot (rotter :code))
(define-registered boolean_rot (rotter :boolean))

(defn flusher
  "Returns a function that empties the stack of the given state."
  [type]
  (fn [state]
    (assoc state type '())))

(define-registered exec_flush (flusher :exec))
(define-registered integer_flush (flusher :integer))
(define-registered float_flush (flusher :float))
(define-registered code_flush (flusher :code))
(define-registered boolean_flush (flusher :boolean))

(defn eqer 
  "Returns a function that compares the top two items of the appropriate stack of 
the given state."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
	    second (stack-ref type 1 state)]
	(->> (pop-item type state)
	     (pop-item type)
	     (push-item (= first second) :boolean)))
      state)))

(define-registered exec_eq (eqer :exec))
(define-registered integer_eq (eqer :integer))
(define-registered float_eq (eqer :float))
(define-registered code_eq (eqer :code))
(define-registered boolean_eq (eqer :boolean))

(defn stackdepther
  "Returns a function that pushes the depth of the appropriate stack of the 
given state."
  [type]
  (fn [state]
    (push-item (count (type state)) :integer state)))

(define-registered exec_stackdepth (stackdepther :exec))
(define-registered integer_stackdepth (stackdepther :integer))
(define-registered float_stackdepth (stackdepther :float))
(define-registered code_stackdepth (stackdepther :code))
(define-registered boolean_stackdepth (stackdepther :boolean))

(defn yanker
  "Returns a function that yanks an item from deep in the specified stack,
using the top integer to indicate how deep."
  [type]
  (fn [state]
    (if (or (and (= type :integer)
		 (not (empty? (rest (type state)))))
	    (and (not (= type :integer))
		 (not (empty? (type state)))
		 (not (empty? (:integer state)))))
      (let [raw-index (stack-ref :integer 0 state)
	    with-index-popped (pop-item :integer state)
	    actual-index (max 0 (min raw-index (- (count (type with-index-popped)) 1)))
	    item (stack-ref type actual-index with-index-popped)
	    with-item-pulled (assoc with-index-popped 
			       type 
			       (let [stk (type with-index-popped)]
				 (concat (take actual-index stk)
					 (rest (drop actual-index stk)))))]
	(push-item item type with-item-pulled))
      state)))

(define-registered exec_yank (yanker :exec))
(define-registered integer_yank (yanker :integer))
(define-registered float_yank (yanker :float))
(define-registered code_yank (yanker :code))
(define-registered boolean_yank (yanker :boolean))

(defn yankduper
  "Returns a function that yanks a copy of an item from deep in the specified stack,
using the top integer to indicate how deep."
  [type]
  (fn [state]
    (if (or (and (= type :integer)
		 (not (empty? (rest (type state)))))
	    (and (not (= type :integer))
		 (not (empty? (type state)))
		 (not (empty? (:integer state)))))
      (let [raw-index (stack-ref :integer 0 state)
	    with-index-popped (pop-item :integer state)
	    actual-index (max 0 (min raw-index (- (count (type with-index-popped)) 1)))
	    item (stack-ref type actual-index with-index-popped)]
	(push-item item type with-index-popped))
      state)))

(define-registered exec_yankdup (yankduper :exec))
(define-registered integer_yankdup (yankduper :integer))
(define-registered float_yankdup (yankduper :float))
(define-registered code_yankdup (yankduper :code))
(define-registered boolean_yankdup (yankduper :boolean))

(defn shover
  "Returns a function that shoves an item deep in the specified stack, using the top
integer to indicate how deep."
  [type]
  (fn [state]
    (if (or (and (= type :integer)
		 (not (empty? (rest (type state)))))
	    (and (not (= type :integer))
		 (not (empty? (type state)))
		 (not (empty? (:integer state)))))
      (let [raw-index (stack-ref :integer 0 state)
	    with-index-popped (pop-item :integer state)
	    item (top-item type with-index-popped)
	    with-args-popped (pop-item type with-index-popped)
	    actual-index (max 0 (min raw-index (count (type with-args-popped))))]
	(assoc with-args-popped type (let [stk (type with-args-popped)]
				       (concat (take actual-index stk)
					       (list item)
					       (drop actual-index stk)))))
      state)))

(define-registered exec_shove (shover :exec))
(define-registered integer_shove (shover :integer))
(define-registered float_shove (shover :float))
(define-registered code_shove (shover :code))
(define-registered boolean_shove (shover :boolean))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; rand instructions

(define-registered boolean_rand
  (fn [state]
    (push-item (random-element [true false]) :boolean state)))

(define-registered integer_rand
  (fn [state]
    (push-item (+ (lrand-int (+ 1 (- max-random-integer min-random-integer)))
		  min-random-integer)
	       :integer
	       state)))

(define-registered float_rand
  (fn [state]
    (push-item (+ (lrand (- max-random-float min-random-float))
		  min-random-float)
	       :float
	       state)))

(define-registered code_rand
  (fn [state]
    (if (not (empty? (:integer state)))
      (push-item (random-code (math/abs (mod (stack-ref :integer 0 state) 
					     max-points-in-random-expressions)) 
			      global-atom-generators)
		 :code
		 (pop-item :integer state))
      state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; instructions for numbers

(defn adder
  "Returns a function that pushes the sum of the top two items."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
	    second (stack-ref type 1 state)]
	(->> (pop-item type state)
	     (pop-item type)
	     (push-item (keep-number-reasonable (+ first second)) type)))
      state)))

(define-registered integer_add (adder :integer))
(define-registered float_add (adder :float))

(defn subtracter
  "Returns a function that pushes the difference of the top two items."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
	    second (stack-ref type 1 state)]
	(->> (pop-item type state)
	     (pop-item type)
	     (push-item (keep-number-reasonable (- second first)) type)))
      state)))

(define-registered integer_sub (subtracter :integer))
(define-registered float_sub (subtracter :float))

(defn multiplier
  "Returns a function that pushes the product of the top two items."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
	    second (stack-ref type 1 state)]
	(->> (pop-item type state)
	     (pop-item type)
	     (push-item (keep-number-reasonable (* second first)) type)))
      state)))

(define-registered integer_mult (multiplier :integer))
(define-registered float_mult (multiplier :float))

(defn divider
  "Returns a function that pushes the quotient of the top two items. Does
nothing if the denominator would be zero."
  [type]
  (fn [state]
    (if (and (not (empty? (rest (type state))))
	     (not (zero? (stack-ref type 0 state))))
      (let [first (stack-ref type 0 state)
	    second (stack-ref type 1 state)]
	(->> (pop-item type state)
	     (pop-item type)
	     (push-item (if (= type :integer)
			  (truncate (keep-number-reasonable (/ second first)))
			  (keep-number-reasonable (/ second first)))
			type)))
      state)))

(define-registered integer_div (divider :integer))
(define-registered float_div (divider :float))

(defn modder
  "Returns a function that pushes the modulus of the top two items. Does
nothing if the denominator would be zero."
  [type]
  (fn [state]
    (if (and (not (empty? (rest (type state))))
	     (not (zero? (stack-ref type 0 state))))
      (let [first (stack-ref type 0 state)
	    second (stack-ref type 1 state)]
	(->> (pop-item type state)
	     (pop-item type)
	     (push-item (if (= type :integer)
			  (truncate (keep-number-reasonable (mod second first)))
			  (keep-number-reasonable (mod second first)))
			type)))
      state)))

(define-registered integer_mod (modder :integer))
(define-registered float_mod (modder :float))

(defn lessthaner
  "Returns a function that pushes the result of < of the top two items onto the 
boolean stack."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
	    second (stack-ref type 1 state)]
	(->> (pop-item type state)
	     (pop-item type)
	     (push-item (< second first) :boolean)))
      state)))

(define-registered integer_lt (lessthaner :integer))
(define-registered float_lt (lessthaner :float))

(defn greaterthaner
  "Returns a function that pushes the result of > of the top two items onto the 
boolean stack."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
	    second (stack-ref type 1 state)]
	(->> (pop-item type state)
	     (pop-item type)
	     (push-item (> second first) :boolean)))
      state)))

(define-registered integer_gt (greaterthaner :integer))
(define-registered float_gt (greaterthaner :float))

(define-registered integer_fromboolean
  (fn
    [state]
    (if (not (empty? (:boolean state)))
      (let [item (stack-ref :boolean 0 state)]
	(->> (pop-item :boolean state)
	     (push-item (if item 1 0) :integer)))
      state)))

(define-registered float_fromboolean
  (fn
    [state]
    (if (not (empty? (:boolean state)))
      (let [item (stack-ref :boolean 0 state)]
	(->> (pop-item :boolean state)
	     (push-item (if item 1.0 0.0) :float)))
      state)))

(define-registered integer_fromfloat
  (fn [state]
    (if (not (empty? (:float state)))
      (let [item (stack-ref :float 0 state)]
	(->> (pop-item :float state)
	     (push-item (truncate item) :integer)))
      state)))

(define-registered float_frominteger
  (fn [state]
    (if (not (empty? (:integer state)))
      (let [item (stack-ref :integer 0 state)]
	(->> (pop-item :integer state)
	     (push-item (* 1.0 item) :float)))
      state)))

(defn minner
  "Returns a function that pushes the minimum of the top two items."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
	    second (stack-ref type 1 state)]
	(->> (pop-item type state)
	     (pop-item type)
	     (push-item (min second first) type)))
      state)))

(define-registered integer_min (minner :integer))
(define-registered float_min (minner :float))

(defn maxer
  "Returns a function that pushes the maximum of the top two items."
  [type]
  (fn [state]
    (if (not (empty? (rest (type state))))
      (let [first (stack-ref type 0 state)
	    second (stack-ref type 1 state)]
	(->> (pop-item type state)
	     (pop-item type)
	     (push-item (max second first) type)))
      state)))

(define-registered integer_max (maxer :integer))
(define-registered float_max (maxer :float))

(define-registered float_sin
  (fn [state]
    (if (not (empty? (:float state)))
      (push-item (keep-number-reasonable (Math/sin (stack-ref :float 0 state)))
		 :float
		 (pop-item :float state))
      state)))

(define-registered float_cos
  (fn [state]
    (if (not (empty? (:float state)))
      (push-item (keep-number-reasonable (Math/cos (stack-ref :float 0 state)))
		 :float
		 (pop-item :float state))
      state)))

(define-registered float_tan
  (fn [state]
    (if (not (empty? (:float state)))
      (push-item (keep-number-reasonable (Math/tan (stack-ref :float 0 state)))
		 :float
		 (pop-item :float state))
      state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; instructions for Booleans

(define-registered boolean_and
  (fn [state]
    (if (not (empty? (rest (:boolean state))))
      (push-item (and (stack-ref :boolean 0 state)
		      (stack-ref :boolean 1 state))
		 :boolean
		 (pop-item :boolean (pop-item :boolean state)))
      state)))

(define-registered boolean_or
  (fn [state]
    (if (not (empty? (rest (:boolean state))))
      (push-item (or (stack-ref :boolean 0 state)
		      (stack-ref :boolean 1 state))
		 :boolean
		 (pop-item :boolean (pop-item :boolean state)))
      state)))

(define-registered boolean_not
  (fn [state]
    (if (not (empty? (:boolean state)))
      (push-item (not (stack-ref :boolean 0 state))
		 :boolean
		 (pop-item :boolean state))
      state)))

(define-registered boolean_frominteger
  (fn [state]
    (if (not (empty? (:integer state)))
      (push-item (not (zero? (stack-ref :integer 0 state)))
		 :boolean
		 (pop-item :integer state))
      state)))

(define-registered boolean_fromfloat
  (fn [state]
    (if (not (empty? (:float state)))
      (push-item (not (zero? (stack-ref :float 0 state)))
		 :boolean
		 (pop-item :float state))
      state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; code and exec instructions

(define-registered code_append
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (let [new-item (concat (ensure-list (stack-ref :code 0 state))
			     (ensure-list (stack-ref :code 1 state)))]
	(if (<= (count-points new-item) global-max-points-in-program)
	  (push-item new-item
		     :code
		     (pop-item :code (pop-item :code state)))
	  state))
      state)))

(define-registered code_atom
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (not (seq? (stack-ref :code 0 state)))
		 :boolean
		 (pop-item :code state))
      state)))

(define-registered code_car
  (fn [state]
    (if (and (not (empty? (:code state)))
	     (> (count (ensure-list (stack-ref :code 0 state))) 0))
      (push-item (first (ensure-list (stack-ref :code 0 state)))
		 :code
		 (pop-item :code state))
      state)))

(define-registered code_cdr
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (rest (ensure-list (stack-ref :code 0 state)))
		 :code
		 (pop-item :code state))
      state)))

(define-registered code_cons
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (let [new-item (cons (stack-ref :code 1 state)
			   (ensure-list (stack-ref :code 0 state)))]
	(if (<= (count-points new-item) global-max-points-in-program)
	  (push-item new-item
		     :code
		     (pop-item :code (pop-item :code state)))
	  state))
      state)))

(define-registered code_do
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (stack-ref :code 0 state) 
		 :exec
		 (push-item 'code_pop :exec state))
      state)))

(define-registered code_do*
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (stack-ref :code 0 state)
		 :exec
		 (pop-item :code state))
      state)))

(define-registered code_do*range
  (fn [state]
    (if (not (or (empty? (:code state))
		 (empty? (rest (:integer state)))))
      (let [to-do (first (:code state))
	    current-index (first (rest (:integer state)))
	    destination-index (first (:integer state))
	    args-popped (pop-item :integer
				  (pop-item :integer
					    (pop-item :code state)))
	    increment (cond (< current-index destination-index) 1
			    (> current-index destination-index) -1
			    true 0)
	    continuation (if (zero? increment)
			   args-popped
			   (push-item (list (+ current-index increment)
					    destination-index
					    'code_quote
					    to-do
					    'code_do*range)
				      :exec
				      args-popped))]
	(push-item to-do :exec (push-item current-index :integer continuation)))
      state)))

(define-registered exec_do*range 
  (fn [state] ; Differs from code.do*range only in the source of the code and the recursive call.
    (if (not (or (empty? (:exec state))
		 (empty? (rest (:integer state)))))
      (let [to-do (first (:exec state))
	    current-index (first (rest (:integer state)))
	    destination-index (first (:integer state))
	    args-popped (pop-item :integer
				  (pop-item :integer
					    (pop-item :exec state)))
	    increment (cond (< current-index destination-index) 1
			    (> current-index destination-index) -1
			    true 0)
	    continuation (if (zero? increment)
			   args-popped
			   (push-item (list (+ current-index increment)
					    destination-index
					    'exec_do*range
					    to-do)
				      :exec
				      args-popped))]
	(push-item to-do :exec (push-item current-index :integer continuation)))
      state)))

(define-registered code_do*count
  (fn [state]
    (if (not (or (empty? (:integer state))
		 (< (first (:integer state)) 1)
		 (empty? (:code state))))
      (push-item (list 0 (dec (first (:integer state))) 'code_quote (first (:code state)) 'code_do*range)
		 :exec
		 (pop-item :integer (pop-item :code state)))
      state)))

(define-registered exec_do*count
  ;; differs from code.do*count only in the source of the code and the recursive call    
  (fn [state] 
    (if (not (or (empty? (:integer state))
		 (< (first (:integer state)) 1)
		 (empty? (:exec state))))
      (push-item (list 0 (dec (first (:integer state))) 'exec_do*range (first (:exec state)))
		 :exec
		 (pop-item :integer (pop-item :exec state)))
      state)))

(define-registered code_do*times
  (fn [state]
    (if (not (or (empty? (:integer state))
		 (< (first (:integer state)) 1)
		 (empty? (:code state))))
      (push-item (list 0 (dec (first (:integer state))) 'code_quote 
		       (cons 'integer_pop 
			     (ensure-list (first (:code state)))) 'code_do*range)
		 :exec
		 (pop-item :integer (pop-item :code state)))
      state)))

(define-registered exec_do*times
  ;; differs from code.do*times only in the source of the code and the recursive call
  (fn [state]
    (if (not (or (empty? (:integer state))
		 (< (first (:integer state)) 1)
		 (empty? (:exec state))))
      (push-item (list 0 (dec (first (:integer state))) 'exec_do*range
		       (cons 'integer_pop (ensure-list (first (:exec state)))))
		 :exec
		 (pop-item :integer (pop-item :exec state)))
      state)))

(define-registered code_map
  (fn [state]
    (if (not (or (empty? (:code state))
		 (empty? (:exec state))))
      (push-item (concat
		  (doall (for [item (ensure-list (first (:code state)))]
			   (list 'code_quote
				 item
				 (first (:exec state)))))
		  '(code_wrap)
		  (doall (for [item (rest (ensure-list (first (:code state))))]
			   'code_cons)))
		 :exec
		 (pop-item :code (pop-item :exec state)))
      state)))

(defn codemaker
  "Returns a function that pops the stack of the given type and pushes the result on 
the code stack."
  [type]
  (fn [state]
    (if (not (empty? (type state)))
      (push-item (first (type state))
		 :code
		 (pop-item type state))
      state)))

(define-registered code_fromboolean (codemaker :boolean))
(define-registered code_fromfloat (codemaker :float))
(define-registered code_frominteger (codemaker :integer))
(define-registered code_quote (codemaker :exec))

(define-registered code_if
  (fn [state]
    (if (not (or (empty? (:boolean state))
		 (empty? (rest (:code state)))))
      (push-item (if (first (:boolean state))
		   (first (rest (:code state)))
		   (first (:code state)))
		 :exec
		 (pop-item :boolean (pop-item :code (pop-item :code state))))
      state)))

(define-registered exec_if
  ;; differs from code.if in the source of the code and in the order of the if/then parts
  (fn [state]
    (if (not (or (empty? (:boolean state))
		 (empty? (rest (:exec state)))))
      (push-item (if (first (:boolean state))
		   (first (:exec state))
		   (first (rest (:exec state))))
		 :exec
		 (pop-item :boolean (pop-item :exec (pop-item :exec state))))
      state)))

(define-registered code_length
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (count (ensure-list (first (:code state))))
		 :integer
		 (pop-item :code state))
      state)))

(define-registered code_list
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (let [new-item (list (first (rest (:code state)))
			   (first (:code state)))]
	(if (<= (count-points new-item) global-max-points-in-program)
	  (push-item new-item
		     :code
		     (pop-item :code (pop-item :code state)))
	  state))
      state)))

(define-registered code_wrap
  (fn [state]
    (if (not (empty? (:code state)))
      (let [new-item (list (first (:code state)))]
	(if (<= (count-points new-item) global-max-points-in-program)
	  (push-item new-item
		     :code
		     (pop-item :code state))
	  state))
      state)))

(define-registered code_member
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (push-item (not (not (some #{(first (rest (:code state)))} 
				 (ensure-list (first (:code state))))))
		 :boolean
		 (pop-item :code (pop-item :code state)))
      state)))

(define-registered exec_noop (fn [state] state))
(define-registered code_noop (fn [state] state))

(define-registered code_nth
  (fn [state]
    (if (not (or (empty? (:integer state))
		 (empty? (:code state))
		 (empty? (ensure-list (first (:code state))))))
      (push-item (nth (ensure-list (first (:code state)))
		      (mod (math/abs (first (:integer state)))
			   (count (ensure-list (first (:code state))))))
		 :code
		 (pop-item :integer (pop-item :code state)))
      state)))

(define-registered code_nthcdr
  (fn [state]
    (if (not (or (empty? (:integer state))
		 (empty? (:code state))
		 (empty? (ensure-list (first (:code state))))))
      (push-item (drop (mod (math/abs (first (:integer state))) 
			    (count (ensure-list (first (:code state)))))
		       (ensure-list (first (:code state))))
		 :code
		 (pop-item :integer (pop-item :code state)))
      state)))

(define-registered code_null
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (let [item (first (:code state))]
		   (not (not (and (seq? item) (empty? item)))))
		 :boolean
		 (pop-item :code state))
      state)))

(define-registered code_size
  (fn [state]
    (if (not (empty? (:code state)))
      (push-item (count-points (first (:code state)))
		 :integer
		 (pop-item :code state))
      state))) 

(define-registered code_extract
  (fn [state]
    (if (not (or (empty? (:code state))
		 (empty? (:integer state))))
      (push-item (code-at-point (first (:code state))
				(first (:integer state)))
		 :code
		 (pop-item :code (pop-item :integer state)))
      state)))

(define-registered code_insert
  (fn [state]
    (if (not (or (empty? (rest (:code state)))
		 (empty? (:integer state))))
      (let [new-item (insert-code-at-point (first (:code state))
					   (first (:integer state))
					   (second (:code state)))]
	(if (<= (count-points new-item) global-max-points-in-program)
	  (push-item new-item
		     :code
		     (pop-item :code (pop-item :code (pop-item :integer state))))
	  state))
      state)))

(define-registered code_subst
  (fn [state]
    (if (not (empty? (rest (rest (:code state)))))
      (let [new-item (subst (stack-ref :code 2 state)
			    (stack-ref :code 1 state)
			    (stack-ref :code 0 state))]
	(if (<= (count-points new-item) global-max-points-in-program)
	  (push-item new-item
		     :code
		     (pop-item :code (pop-item :code (pop-item :code state))))
	  state))
      state)))

(define-registered code_contains
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (push-item (contains-subtree (stack-ref :code 1 state)
				   (stack-ref :code 0 state))
		 :boolean
		 (pop-item :code (pop-item :code state)))
      state)))

(define-registered code_container
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (push-item (containing-subtree (stack-ref :code 0 state)
				     (stack-ref :code 1 state))
		 :code
		 (pop-item :code (pop-item :code state)))
      state)))

(define-registered code_position
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (push-item (or (first (seq-utils/positions #{(stack-ref :code 1 state)}
						 (ensure-list (stack-ref :code 0 state))))
		     -1)
		 :integer
		 (pop-item :code (pop-item :code state)))
      state)))

(define-registered code_discrepancy
  (fn [state]
    (if (not (empty? (rest (:code state))))
      (push-item (discrepancy (stack-ref :code 0 state) (stack-ref :code 1 state))
		 :integer
		 (pop-item :code (pop-item :code state)))
      state)))

(define-registered exec_k
  (fn [state]
    (if (not (empty? (rest (:exec state))))
      (push-item (first (:exec state))
		 :exec
		 (pop-item :exec (pop-item :exec state)))
      state)))

(define-registered exec_s
  (fn [state]
    (if (not (empty? (rest (rest (:exec state)))))
      (let [stk (:exec state)
	    x (first stk)
	    y (first (rest stk))
	    z (first (rest (rest stk)))]
	(if (<= (count-points (list y z)) global-max-points-in-program)
	  (push-item x
		     :exec
		     (push-item z
				:exec
				(push-item (list y z)
					   :exec
					   (pop-item :exec 
						     (pop-item :exec 
							       (pop-item :exec state))))))
	  state))
      state)))

(define-registered exec_y
  (fn [state]
    (if (not (empty? (:exec state)))
      (let [new-item (list 'exec_y (first (:exec state)))]
	(if (<= (count-points new-item) global-max-points-in-program)
	  (push-item (first (:exec state))
		     :exec
		     (push-item new-item
				:exec
				(pop-item :exec state)))
	  state))
      state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; print all registered instructions on loading

(printf "\nRegistered instructions: %s\n\n" registered-instructions)
(flush)

;; also set default value for atom-generators
(def global-atom-generators (concat registered-instructions
				    (list (fn [] (lrand-int 100))
					  (fn [] (lrand)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; push interpreter

(defn recognize-literal
  "If thing is a literal, return its type -- otherwise return false."
  [thing]
  (cond (integer? thing) :integer
	(number? thing) :float
        (or (= thing true) (= thing false)) :boolean
	;; if names are added then distinguish them from registered instructions here
	true false))

(def debug-recent-instructions ())

(defn execute-instruction
  "Executes a single Push instruction."
  [instruction state]
  ;; for debugging only, e.g. for stress-test
  ;(def debug-recent-instructions (cons instruction debug-recent-instructions))
  ;(def debug-recent-state state)
  (if (not instruction) ;; tests for nil and ignores it
    state
    (let [literal-type (recognize-literal instruction)]
      (if literal-type
	(push-item instruction literal-type state)
	((instruction instruction-table) state)))))

(defn eval-push 
  "Executes the contents of the exec stack, aborting prematurely if execution limits are 
exceeded. The resulting push state will map :termination to :normal if termination was 
normal, or :abnormal otherwise."
  ([state] (eval-push state false))
  ([state print]
     (loop [iteration 1 s state
	    time-limit (+ evalpush-time-limit (System/nanoTime))]
       (if (or (> iteration evalpush-limit)
	       (empty? (:exec s))
	       (> (System/nanoTime) time-limit))
	   (assoc s :termination (if (<= iteration evalpush-limit) :normal :abnormal))
	   (let [exec-top (top-item :exec s)
		 s (pop-item :exec s)]
	     (let [s (if (seq? exec-top)
		       (assoc s :exec (concat exec-top (:exec s)))
		       (execute-instruction exec-top s))]
	       (when print
		 (printf "\nState after %s steps (last step: %s):\n" 
			 iteration (if (seq? exec-top) "(...)" exec-top))
		 (state-pretty-print s))
	       (recur (inc iteration) s time-limit)))))))

(defn run-push 
  "The top level of the push interpreter; calls eval-schush between appropriate code/exec 
pushing/popping. The resulting push state will map :termination to :normal if termination was 
normal, or :abnormal otherwise."
  ([code state]
     (run-push code state false))
  ([code state print]
     (let [s (if top-level-push-code (push-item code :code state) state)]
       (let [s (push-item code :exec s)]
	 (when print
	   (printf "\nState after 0 steps:\n")
	   (state-pretty-print s))
	 (let [s (eval-push s print)]
	   (if top-level-pop-code
	     (pop-item :code s)
	     s))))))

(defn error-function
  "Dummy error-function to be replaced."
  [program] 1)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; pushgp

;; Individuals are structure maps.
;; Populations are vectors of agents with individuals as their states (along with error and
;; history information).

(defstruct individual :program :errors :total-error :history :ancestors)

(defn auto-simplify 
  "Auto-simplifies the provided individual."
  [ind steps print? progress-interval]
  (when print? (printf "\nAuto-simplifying with starting size: %s" (count-points (:program ind))))
  (loop [step 0 program (:program ind) errors (:errors ind) total-errors (:total-error ind)]
    (when (and print? 
	       (or (>= step steps)
		   (zero? (mod step progress-interval))))
      (printf "\nstep: %s\nprogram: %s\nerrors: %s\ntotal: %s\nsize: %s\n" 
	      step (not-lazy program) (not-lazy errors) total-errors (count-points program))
      (flush))
    (if (>= step steps)
      (struct-map individual :program program :errors errors :total-error total-errors 
		  :history (:history ind) 
		  :ancestors (if maintain-ancestors
			       (cons (:program ind) (:ancestors ind))
			       (:ancestors ind)))
      (let [new-program (if (< (lrand-int 5) 4)
			  ;; remove a small number of random things
			  (loop [p program how-many (inc (lrand-int 2))]
			    (if (zero? how-many)
			      p
			      (recur (remove-code-at-point p (lrand-int (count-points p)))
				     (dec how-many))))
			  ;; flatten something
			  (let [point-index (lrand-int (count-points program))
				point (code-at-point program point-index)]
			    (if (seq? point)
			      (insert-code-at-point program point-index (seq-utils/flatten point))
			      program)))
	    new-errors (error-function new-program)
	    new-total-errors (apply + new-errors)]
	(if (<= new-total-errors total-errors)
	  (recur (inc step) new-program new-errors new-total-errors)
	  (recur (inc step) program errors total-errors))))))

(defn problem-specific-report
  "Customize this for your own problem. It will be called at the end of the generational report."
  [best population generation report-simplifications]
  :no-problem-specific-report-function-defined)

(defn report 
  "Reports on the specified generation of a pushgp run. Returns the best
  individual of the generation."
  [population generation report-simplifications]
  (printf "\n\n;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;")(flush)
  (printf "\n;; -*- Report at generation %s" generation)(flush)
  (let [sorted (sort-by :total-error < population)
	best (first sorted)]
    (printf "\nBest program: %s" (not-lazy (:program best)))(flush)
    (when (> report-simplifications 0)
      (printf "\nPartial simplification (may beat best): %s"
	      (not-lazy (:program (auto-simplify best report-simplifications false 1000)))))
    (flush)
    (printf "\nErrors: %s" (not-lazy (:errors best)))(flush)
    (printf "\nTotal: %s" (:total-error best))(flush)
    (printf "\nHistory: %s" (not-lazy (:history best)))(flush)
    (printf "\nSize: %s" (count-points (:program best)))(flush)
    (print "\n--- Population Statistics ---\nAverage total errors in population: ")(flush)
    (print (* 1.0 (/ (reduce + (map :total-error sorted)) (count population))))(flush)
    (printf "\nMedian total errors in population: %s"
	    (:total-error (nth sorted (truncate (/ (count sorted) 2)))))(flush)
    (printf "\nAverage program size in population (points): %s"
	    (* 1.0 (/ (reduce + (map count-points (map :program sorted)))
		      (count population))))(flush)
    (printf "\n;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;\n\n")
    (flush)
    (problem-specific-report best population generation report-simplifications)
    best))

(defn select
  "Conducts a tournament and returns the individual with the lower total error."
  [pop tournament-size radius location]
  (let [tournament-set 
	(doall
	 (for [_ (range tournament-size)]
	   (nth pop
		(if (zero? radius)
		  (lrand-int (count pop))
		  (mod (+ location (- (lrand-int (+ 1 (* radius 2))) radius))
		       (count pop))))))]
     (reduce (fn [i1 i2] (if (< (:total-error i1) (:total-error i2)) i1 i2))
	     tournament-set)))

(defn mutate 
  "Returns a mutated version of the given individual."
  [ind mutation-max-points max-points atom-generators]
  (let [new-program (insert-code-at-point (:program ind) 
					  (lrand-int (count-points (:program ind)))
					  (random-code mutation-max-points atom-generators))]
    (if (> (count-points new-program) max-points)
      ind
      (struct-map individual :program new-program :history (:history ind)
		  :ancestors (if maintain-ancestors
			       (cons (:program ind) (:ancestors ind))
			       (:ancestors ind))))))

(defn crossover 
  "Returns a copy of parent1 with a random subprogram replaced with a random 
subprogram of parent2."
  [parent1 parent2 max-points]
  (let [new-program (insert-code-at-point 
		     (:program parent1) 
		     (lrand-int (count-points (:program parent1)))
		     (code-at-point (:program parent2)
				    (lrand-int (count-points (:program parent2)))))]
    (if (> (count-points new-program) max-points)
      parent1
      (struct-map individual :program new-program :history (:history parent1)
		  :ancestors (if maintain-ancestors
			       (cons (:program parent1) (:ancestors parent1))
			       (:ancestors parent1))))))

(defn evaluate-individual
  "Returns the given individual with errors and total-errors, computing them if necessary."
  [i]
  (let [  p (:program i)
	  e (if (seq? (:errors i))
	      (:errors i)
	      (error-function p))
	  te (if (number? (:total-error i))
	       (:total-error i)
	       (keep-number-reasonable (reduce + e)))]
      (struct-map individual :program p :errors e :total-error te 
		  :history (if maintain-histories (cons te (:history i)) (:history i))
		  :ancestors (:ancestors i))))

(defn breed-and-eval
  [agt location population population-size max-points atom-generators 
   mutation-probability  mutation-max-points crossover-probability simplification-probability 
   tournament-size reproduction-simplifications trivial-geography-radius]
  (evaluate-individual
    (let [n (lrand)]
        (cond 
         ;; mutation
         (< n mutation-probability)
         (mutate (select population tournament-size trivial-geography-radius location) 
                 mutation-max-points max-points atom-generators)
         ;; crossover
         (< n (+ mutation-probability crossover-probability))
         (let [first-parent (select population tournament-size trivial-geography-radius location)
               second-parent (select population tournament-size trivial-geography-radius location)]
           (crossover first-parent second-parent max-points))
         ;; simplification
         (< n (+ mutation-probability crossover-probability simplification-probability))
         (auto-simplify (select population tournament-size trivial-geography-radius location)
                         reproduction-simplifications false 1000)
         ;; replication
         true 
         (select population tournament-size trivial-geography-radius location)))))

(sevak/defsevak island-breed
  [population island-population-size max-points atom-generators
   mutation-probability mutation-max-points crossover-probability 
   simplification-probability tournament-size reproduction-simplifications 
   trivial-geography-radius]
  (vec (doall (for [i (range island-population-size)] (breed-and-eval (nth population i) i population island-population-size max-points
                                                                         atom-generators mutation-probability mutation-max-points crossover-probability 
                                                                         simplification-probability tournament-size reproduction-simplifications 
                                                                         trivial-geography-radius)))))

(defmacro print-params
  [params]
  (cons 'do (doall (map #(list 'println (str %) "=" %) params))))

(defn pushgp
  "The top-level routine of pushgp."
  [params]
  (let [error-threshold (get params :error-threshold 0)
	population-size (get params :population-size 1000)
	max-points (get params :max-points 50)
	atom-generators (get params :atom-generators (concat registered-instructions
							     (list '(fn [] (lrand-int 100))
								   '(fn [] (lrand)))))
	max-generations (get params :max-generations 1001)
	mutation-probability (get params :mutation-probability 0.4)
	mutation-max-points (get params :mutation-max-points 20)
	crossover-probability (get params :crossover-probability 0.4)
	simplification-probability (get params :simplification-probability 0.1)
	tournament-size (get params :tournament-size 7)
	report-simplifications (get params :report-simplifications 100)
	final-report-simplifications (get params :final-report-simplifications 1000)
	reproduction-simplifications (get params :reproduction-simplifications 1)
	trivial-geography-radius (get params :trivial-geography-radius 0)
        num-islands 2]
    ;; set globals from parameters
    (def global-atom-generators atom-generators)
    (def global-max-points-in-program max-points)
    (printf "\nStarting PushGP run.\n\n") (flush)
    (print-params 
     (error-threshold population-size max-points atom-generators max-generations 
		     mutation-probability mutation-max-points crossover-probability
		     simplification-probability tournament-size report-simplifications
		     final-report-simplifications trivial-geography-radius))
    (printf "\nGenerating initial population and computing errors...\n") (flush)
    (let [population (ref (doall (map evaluate-individual
                                      (vec (doall (for [_ (range population-size)] 
                                                     (struct-map individual 
                                                        :program (random-code max-points atom-generators))))))))]
      (loop [generation 0]
	(printf "\n\n-----\nProcessing generation: %s" generation) (flush)
	;; report and check for success
	(let [best (report (vec @population) generation report-simplifications)]
	  (if (<= (:total-error best) error-threshold)
	    (do (printf "\n\nSUCCESS at generation %s\nSuccessful program: %s\nErrors: %s\nTotal error: %s\nHistory: %s\nSize: %s\n\n"
			generation (not-lazy (:program best)) (not-lazy (:errors best)) (:total-error best) 
			(not-lazy (:history best)) (count-points (:program best)))
		(when print-ancestors-of-solution
		  (printf "\nAncestors of solution:\n")
		  (println (:ancestors best)))
		(auto-simplify best final-report-simplifications true 500))
	    (do (if (>= generation max-generations)
		  (do (printf "\nFAILURE\n"))
		  (do (printf "\nProducing offspring...") (flush)
		      (let [island-populations (partition (/ population-size num-islands) @population)
                            island-population-size (/ population-size num-islands)
                            island-computations (doall (map #(island-breed %
                                                                island-population-size max-points atom-generators 
                                                                mutation-probability mutation-max-points crossover-probability 
                                                                simplification-probability tournament-size reproduction-simplifications 
                                                                trivial-geography-radius)
                                                            island-populations))]
                        (client/wait-until-completion island-computations 9999999)
                        (printf "\nInstalling next generation...") (flush)
                        (dosync (ref-set population (apply concat (for [k island-computations] (k :value))))))
                      (recur (inc generation)))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; boot server

(defn boot-server [] (sevak/boot-sevak-server))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; stress test

(defn stress-test
  "Performs a stress test of the registered instructions by generating and running n
random programs. For more thorough testing and debugging of Push instructions you many
want to un-comment code in execute-instruction that will allow you to look at recently
executed instructions and the most recent state after an error. That code burns memory,
however, so it is normally commented out. You might also want to comment out the handling
of nil values in execute-instruction, do see if any instructions are introducing nils."
  [n]
  (let [completely-random-program
        (fn []
          (random-code 100 (concat registered-instructions
                                   (list (fn [] (lrand-int 100))
					 (fn [] (lrand))))))]
    (loop [i 0 p (completely-random-program)]
      (if (>= i n)
        (println :no-errors-found-in-stress-test)
        (let [result (run-push p (make-push-state) false)]
          (if result
	    (recur (inc i) (completely-random-program))
	    (println p)))))))

;(stress-test 10000)
