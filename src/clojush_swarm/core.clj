(ns clojush-swarm.core
  (:use clojure.contrib.command-line)
  (:require [clojush-swarm.clojush :as c])
  (:gen-class))

(import '(java.io FileWriter BufferedWriter File))
(import '(java.io FileWriter FileReader))
(import '(org.apache.commons.io FileUtils))


(defn -main [& args]
  (with-command-line args "Distributed Clojush!" 
   [[file f "Problem to run.  Should include an error-function." "/home/brian/src/swarm/clojush-swarm/src/clojush_swarm/examples/odd.clj"]
    [server? s? "Run as a server (available to compute client tasks)."]
    [verbose? v? "Prints all calls to sevaks (with full args)"]
    [logs-dir l "Directory where logs should go." "/home/brian/src/swarm/clojush-swarm/src/logs"]
    [logs-prefix "Prefix for log file names." "clojush"]
    etc]

  (binding [*ns* (the-ns 'clojush-swarm.clojush)]
    (load-file file)
    (intern 'org.rathore.amit.utils.config
      '*clj-utils-config*
      {:log-to-console (if verbose? true false)
       :logs-dir logs-dir
       :log-filename-prefix logs-prefix
       :exception-notifier {:enabled false}})
    (intern 'org.rathore.amit.utils.file
      'spit
        (fn [f content] 
          (let [file (File. f)]
            (if (not (.exists file))
              (FileUtils/touch file))
            (with-open [#^FileWriter fw (FileWriter. f true)]
              (with-open [#^BufferedWriter bw (BufferedWriter. fw)]
                (.write bw (str content "\n")))))))
    (c/boot-server)
    (if (not server?)
      (c/pushgp {:atom-generators c/registered-instructions})))))
