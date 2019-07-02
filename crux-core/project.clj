(defproject juxt/crux-core :derived-from-git
  :description "Crux Core"
  :url "https://github.com/juxt/crux"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/tools.logging "0.4.1"]
                 [org.clojure/spec.alpha "0.2.176"]
                 [com.stuartsierra/dependency "0.2.0"]
                 [com.taoensso/nippy "2.14.0"]
                 [org.agrona/agrona "1.0.0"]]
  :profiles {:dev {:dependencies [[org.clojure/test.check "0.10.0-alpha3"]
                                  [ch.qos.logback/logback-classic "1.2.3"]]}}
  :middleware [leiningen.project-version/middleware]
  :java-source-paths ["src"]
  :javac-options ["-source" "8" "-target" "8"
                  "-XDignore.symbol.file"
                  "-Xlint:all,-options,-path"
                  "-Werror"
                  "-proc:none"])