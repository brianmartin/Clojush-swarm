(def operation-configs {
    "development" {
      :swarmiji-username "brian"
      :host "127.0.0.1"
      :port 61613
      :q-username "guest"
      :q-password "guest"
      :sevak-request-queue-prefix "clojush_"
      :sevak-diagnostics-queue-prefix "clojush_diagnostics_"    
      :distributed-mode true
      :diagnostics-mode false
      :logsdir (str swarmiji-home "/logs")
      :log-to-console true }
})

(def swarmiji-mysql-configs {
      "test" {
             :classname "com.mysql.jdbc.Driver"
             :subprotocol "mysql"
             :user "root"
             :password "password"
             :subname (str "//localhost/swarmiji_development")
      }
   }
)

