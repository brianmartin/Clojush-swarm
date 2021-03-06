;;;;;;;;;;;;
;; Integer symbolic regression of factorial, using an input instruction and 
;; lots of other instructions, a fairly large population (5000), and trivial
;; geography. Hard but solvable.

(define-registered in 
  (fn [state] (push-item (stack-ref :auxiliary 0 state) :integer state)))

(defn factorial 
  "Returns the factorial of n. Just used to set up fitness cases here, so
  efficiency isn't a concern."
  [n]
  (if (< n 2)
    1
    (* n (factorial (- n 1)))))

(defn error-function [program]
 (doall
  (for [input (range 1 6)]
    (let [state (run-push program
                          (push-item input :auxiliary
                                     (push-item input :integer
                                                (make-push-state))))
          top-int (top-item :integer state)]
      (if (number? top-int)
        (math/abs (- top-int (factorial input)))
        1000000000))))) ;; big penalty, since errors can be big

(def pushgp-args 
  {:atom-generators (concat (registered-for-type :integer)
                            (registered-for-type :exec)
                            (registered-for-type :boolean)
                            (list (fn [] (rand-int 100))
                                      'in))
   :max-points 100
   :population-size 5000
   :trivial-geography-radius 10})
