(ns validaze.refinements
  (:require [clj-time.format :refer [parse formatters]]))

(def user-defined-refinements
  {:nonnegative-integer [:integer [nat-int? (fn [_] "must be a nonnegative integer")]]
   :positive-integer    [:integer [pos-int? (fn [_] "must be a positive integer")]]
   :datetime            [:string  [#(and (string? %1)
                                         (try (some? (parse (formatters :date-time) %1))
                                              (catch Exception _ false)))
                                   (fn [_] "must be a datetime")]]})
