(ns validaze.examples.storefront
  "A tracking plan for an online store's analytics events.

  It exercises every part of the schema language:

  - refinements: predicate-based (`:sku`, `:uuid`), set-based (`:currency`)
    and refinements of refinements (`:price-cents` refines the built-in
    `:nonnegative-integer`)
  - `[:enum ...]`, `[:list ...]` and `[:object ...]` types, nested
  - a property list (`:product`) shared by several events
  - a versioned migration: `order_completed` v1 sent `total` as float
    dollars; v2 deletes it in favour of integer `total_cents`
  - conditional requirements (`discount_cents` when `coupon_code` exists,
    `shipping_country` when `fulfillment` is \"delivery\")
  - versioned super-properties sent with every event"
  (:require [validaze.core :as validaze]))

;; Refinement predicates are called with arbitrary JSON values when the
;; validator is built (validaze checks them against their spec), so they
;; must accept any input and return a boolean. `matches` does both.
(defn- matches [re]
  (fn [v] (and (string? v) (boolean (re-matches re v)))))

(defn- between [lo hi]
  (fn [v] (and (integer? v) (<= lo v hi))))

(def refinements
  {:currency     #{"USD" "EUR" "GBP" "CAD"}
   :country-code [:string [(matches #"[A-Z]{2}")
                           (fn [_] "must be an ISO 3166-1 alpha-2 country code, e.g. \"US\"")]]
   :uuid         [:string [(matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                           (fn [_] "must be a lowercase UUID")]]
   :sku          [:string [(matches #"[A-Z]{2,4}-\d{3,6}")
                           (fn [v] (str "must be a SKU like \"TEE-1042\", got " (pr-str v)))]]
   :semver       [:string [(matches #"\d+\.\d+\.\d+")
                           (fn [_] "must be a semantic version, e.g. \"4.2.0\"")]]
   :locale       [:string [(matches #"[a-z]{2}(-[A-Z]{2})?")
                           (fn [_] "must be a locale such as \"en\" or \"en-US\"")]]
   :search-query [:string [(fn [v] (and (string? v) (<= 1 (count (.trim ^String v)) 200)))
                           (fn [_] "must be 1-200 non-blank characters")]]
   ;; Refines the built-in :nonnegative-integer, which refines :integer.
   :price-cents  [:nonnegative-integer [(between 0 10000000)
                                        (fn [_] "must be at most 10000000 cents")]]
   :quantity     [:positive-integer [(between 1 99)
                                     (fn [_] "must be between 1 and 99")]]})

;; Types for every property any event may carry. It is a vector of maps so
;; related properties can be grouped; the maps are merged.
(def properties-schema
  [;; product
   {"sku"              :sku
    "product_name"     :string
    "price_cents"      :price-cents
    "currency"         :currency
    "category"         [:enum "apparel" "footwear" "accessories" "home"]
    "variant"          :string
    "list_position"    :positive-integer}
   ;; cart and checkout
   {"cart_id"          :uuid
    "quantity"         :quantity
    "item_count"       :positive-integer
    "subtotal_cents"   :price-cents
    "skus"             [:list :sku]}
   ;; search
   {"query"            :search-query
    "result_count"     :nonnegative-integer
    ;; e.g. {"size" ["m" "l"], "color" ["black"]}
    "filters"          [:object :string [:list :string]]}
   ;; orders
   {"order_id"         :uuid
    "total"            :number
    "total_cents"      :price-cents
    "coupon_code"      :string
    "discount_cents"   :price-cents
    "fulfillment"      [:enum "delivery" "pickup"]
    "shipping_country" :country-code
    "payment_method"   [:enum "card" "paypal" "apple_pay" "gift_card"]
    "quantities"       [:object :sku :quantity]}])

;; Requiredness for properties shared by several events. Events pull these
;; in with `:includes`.
(def property-lists
  {:product {"sku"          {:required? true}
             "product_name" {:required? true}
             "price_cents"  {:required? true}
             "currency"     {:required? true}
             "category"     {:required? false}}})

;; event type -> version -> property -> attributes. Each version lists only
;; what changed since the previous one; `nil` deletes a property.
(def events-schema
  {"product_viewed"
   {1 {:includes [:product]}
    2 {"variant"       {:required? false}
       "list_position" {:required? false}}}

   "product_added_to_cart"
   {1 {:includes [:product]
       "cart_id"  {:required? true}
       "quantity" {:required? true}}}

   "products_searched"
   {1 {"query"        {:required? true}
       "result_count" {:required? true}
       "filters"      {:required? false}}}

   "checkout_started"
   {1 {"cart_id"        {:required? true}
       "item_count"     {:required? true}
       "subtotal_cents" {:required? true}
       "currency"       {:required? true}
       "skus"           {:required? true}}}

   "order_completed"
   {1 {"order_id" {:required? true}
       "total"    {:required? true}
       "currency" {:required? true}
       "skus"     {:required? true}}
    2 {"total"            nil
       "total_cents"      {:required? true}
       "coupon_code"      {:required? false}
       "discount_cents"   {:required? [:when "coupon_code" :exists]}
       "fulfillment"      {:required? true}
       "shipping_country" {:required? [:when "fulfillment" #{"delivery"}]}}
    3 {"payment_method" {:required? false}
       "quantities"     {:required? false}}}})

;; Sent with every event, inside "properties". Versions are additive: each
;; may only add properties. Events choose one with "super_properties_version"
;; (default: 1).
(def super-properties-schema
  {1 {"anonymous_id" {:type :uuid :required? true}
      "platform"     {:type [:enum "web" "ios" "android"] :required? true}}
   2 {"user_id"      {:type :uuid :required? false}
      "locale"       {:type :locale :required? false}
      "app_version"  {:type :semver :required? [:when "platform" #{"ios" "android"}]}}})

(def validate
  (validaze/validator events-schema properties-schema super-properties-schema
                      property-lists refinements))

;; Sample events. Each invalid event is paired with the set of messages
;; `validate` returns for it (messages come back in no particular order).

(def ^:private web {"anonymous_id" "1b4e28ba-2fa1-41d2-883f-0016d3cca427" "platform" "web"})

(def valid-events
  {:product-viewed
   {"event_type" "product_viewed" "event_version" 2
    "properties" (merge web {"sku" "TEE-1042" "product_name" "Heavyweight Tee" "price_cents" 3200
                             "currency" "USD" "category" "apparel" "list_position" 3})}

   :products-searched
   {"event_type" "products_searched" "event_version" 1
    "properties" (merge web {"query" "linen shirt" "result_count" 18
                             "filters" {"size" ["m" "l"] "color" ["white"]}})}

   :order-completed-v1
   {"event_type" "order_completed" "event_version" 1
    "properties" (merge web {"order_id" "9f8c2a6e-5d1b-4c3a-8e7f-2b6d4a1c0e93" "total" 64.0
                             "currency" "USD" "skus" ["TEE-1042" "TEE-1042"]})}

   :order-completed-v3-mobile
   {"event_type" "order_completed" "event_version" 3 "super_properties_version" 2
    "properties" {"anonymous_id" "1b4e28ba-2fa1-41d2-883f-0016d3cca427" "platform" "ios"
                  "app_version" "4.2.0" "user_id" "7c9e6679-7425-40de-944b-e07fc1f90ae7"
                  "order_id" "9f8c2a6e-5d1b-4c3a-8e7f-2b6d4a1c0e93" "total_cents" 5760 "currency" "USD"
                  "skus" ["TEE-1042"] "coupon_code" "FALL10" "discount_cents" 640
                  "fulfillment" "delivery" "shipping_country" "US"
                  "payment_method" "apple_pay" "quantities" {"TEE-1042" 2}}}})

(def invalid-events
  {:missing-required-property
   [{"event_type" "product_added_to_cart" "event_version" 1
     "properties" (merge web {"sku" "TEE-1042" "product_name" "Heavyweight Tee" "price_cents" 3200
                              "currency" "USD" "quantity" 1})}
    #{"Missing required keys: [\"cart_id\"]."}]

   :property-deleted-in-later-version
   [{"event_type" "order_completed" "event_version" 2
     "properties" (merge web {"order_id" "9f8c2a6e-5d1b-4c3a-8e7f-2b6d4a1c0e93" "total" 57.6
                              "total_cents" 5760 "currency" "USD" "skus" ["TEE-1042"]
                              "fulfillment" "pickup"})}
    #{"Unexpected keys: [\"total\"]."}]

   :bad-values
   [{"event_type" "checkout_started" "event_version" 1
     "properties" (merge web {"cart_id" "c0ffee" "item_count" 0 "subtotal_cents" 3200
                              "currency" "JPY" "skus" ["TEE-1042" "tee-1042"]})}
    #{"'cart_id' must be a lowercase UUID"
      "'currency' must be one of: #{\"EUR\" \"GBP\" \"CAD\" \"USD\"}"
      "'item_count' must be a positive integer"
      "'skus' must be a vector of type 'sku' and 'sku' must be a SKU like \"TEE-1042\", got \"tee-1042\""}]

   :conditional-requirement
   [{"event_type" "order_completed" "event_version" 2
     "properties" (merge web {"order_id" "9f8c2a6e-5d1b-4c3a-8e7f-2b6d4a1c0e93" "total_cents" 5760
                              "currency" "USD" "skus" ["TEE-1042"] "fulfillment" "delivery"})}
    #{"'shipping_country' is required when 'fulfillment' is any of: #{\"delivery\"}"}]

   :mobile-super-property
   [{"event_type" "products_searched" "event_version" 1 "super_properties_version" 2
     "properties" {"anonymous_id" "1b4e28ba-2fa1-41d2-883f-0016d3cca427" "platform" "android"
                   "query" "boots" "result_count" 4}}
    #{"'app_version' is required when 'platform' is any of: #{\"ios\" \"android\"}"}]

   :unknown-version
   [{"event_type" "products_searched" "event_version" 2 "properties" web}
    #{"There is no version 2 for event 'products_searched'"}]})
