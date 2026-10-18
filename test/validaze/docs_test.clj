(ns validaze.docs-test
  "Runs the code in the documentation.

  Each ```clojure block in a doc is evaluated, in order, in a fresh
  namespace per file. A form followed by

      ;; => value

  must evaluate to value (which may continue on further `;;` lines), and
  a form followed by

      ;; throws=> \"text\"

  must throw an exception whose message contains text."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer :all]))

(def docs ["README.md" "docs/guide.md"])

(defn- code-blocks [text]
  (map second (re-seq #"(?ms)^```clojure\n(.*?)^```" text)))

(defn- expectation-line [line]
  (when-let [[_ kind body] (re-matches #"\s*;; (=>|throws=>) ?(.*)" line)]
    [kind body]))

(defn- continuation-line [line]
  (second (re-matches #"\s*;;\s{2,}(.*)" line)))

(defn- tokenize
  "Rewrites expectation comments into marker forms the reader can see."
  [block]
  (loop [[line & more] (string/split-lines block), out [], open? false]
    (cond
      (nil? line) (string/join "\n" (cond-> out open? (conj ")")))
      (expectation-line line)
      (let [[kind body] (expectation-line line)]
        (recur more (cond-> out open? (conj ")")
                      true (conj (str "(:validaze.docs/" kind " " body)))
               true))
      (and open? (continuation-line line))
      (recur more (conj out (continuation-line line)) true)
      :else
      (recur more (cond-> out open? (conj ")") true (conj line)) false))))

(defn- read-forms [text]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. text))]
    (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof} rdr)))))

(defn- expectation? [form]
  (and (seq? form) (#{:validaze.docs/=> :validaze.docs/throws=>} (first form))))

(defn- run-doc [path]
  (let [ns-sym (gensym "validaze.docs.")
        forms (mapcat (comp read-forms tokenize) (code-blocks (slurp path)))]
    (binding [*ns* (create-ns ns-sym)]
      (refer-clojure)
      (loop [[form & more] forms, last-result nil, last-thrown nil, last-form nil]
        (cond
          (nil? form) nil

          (expectation? form)
          (let [[kind expected] form]
            (if (= kind :validaze.docs/=>)
              (do (is (nil? last-thrown)
                      (str path ": " (pr-str last-form) " threw " last-thrown))
                  (is (= expected last-result)
                      (str path ": " (pr-str last-form) "\n  returned " (pr-str last-result))))
              (is (and last-thrown (string/includes? (.getMessage ^Throwable last-thrown) expected))
                  (str path ": " (pr-str last-form) " should throw \"" expected "\", got "
                       (if last-thrown (.getMessage ^Throwable last-thrown) (pr-str last-result)))))
            (recur more nil nil nil))

          :else
          (let [[result thrown] (try [(eval form) nil] (catch Throwable t [nil t]))]
            ;; An unexpected exception is a failure unless an expectation follows.
            (when (and thrown (not (expectation? (first more))))
              (is false (str path ": " (pr-str form) " threw " thrown)))
            (recur more result thrown form)))))
    (remove-ns ns-sym)))

(deftest documentation-examples-run
  (doseq [path docs]
    (is (.exists (io/file path)) path)
    (run-doc path)))
