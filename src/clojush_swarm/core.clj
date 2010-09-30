(ns clojush-swarm.core
  (:use clojure.contrib.command-line
        clojush-swarm.clojush)
  (:gen-class))

(defn -main [& args]
  (with-command-line args "Distributed Clojush!" 
   [[file f "Problem to run.  Should include an error-function." "/home/brian/src/swarm/clojush-swarm/src/clojush_swarm/examples/odd.clj"]
    [server? s? "Run as a server (available to compute client tasks)." false]
    [distributed? d? "Run in distributed mode." false]
    etc]
  (in-ns 'clojush-swarm.clojush)
  (load-file file)
  (boot-server)
  (if (not server?)
    (pushgp {:atom-generators registered-instructions}))))
