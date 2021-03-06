(ns biff.auth
  (:require
    [biff.crux :as bcrux]
    [biff.util :as bu]
    [byte-streams :as bs]
    [byte-transforms :as bt]
    [clojure.string :as str]
    [crux.api :as crux]
    [crypto.random :as random]
    [lambdaisland.uri :as uri]
    [ring.middleware.anti-forgery :as anti-forgery]
    [ring.middleware.token :as token])
  (:import
    [com.auth0.jwt.algorithms Algorithm]
    [com.auth0.jwt JWT]))

(defn encode [claims {:keys [secret alg]}]
  ; todo maybe add more algorithms
  (let [alg (case alg
              :HS256 (Algorithm/HMAC256 secret))]
    (->
      (reduce (fn [token [k v]]
                (.withClaim token (name k) v))
        (JWT/create)
        claims)
      (.sign alg))))

(defn get-key [{:keys [biff/node biff/db k] :as env}]
  (or (get (crux/entity db :biff.auth/keys) k)
    (doto (bs/to-string (bt/encode (random/bytes 16) :base64))
      (#(bcrux/submit-tx
          env
          {[:biff/auth-keys :biff.auth/keys]
           {:db/merge true
            k %}})))))

(defn jwt-key [env]
  (get-key (assoc env :k :jwt-key)))

(defn signin-token [jwt-key claims]
  (encode
    (merge
      claims
      {:iss "biff"
       :iat (java.util.Date.)
       :exp (bu/add-seconds (java.util.Date.) (* 60 60 24))})
    {:secret jwt-key
     :alg :HS256}))

(defn signin-link [{:keys [claims url] :as env}]
  (let [jwt (signin-token (jwt-key env) (update claims :email str/trim))]
    (-> url
      uri/uri
      (uri/assoc-query :token jwt)
      str)))

(defn email= [s1 s2]
  (.equalsIgnoreCase s1 s2))

(defn send-signin-link [{:keys [params/email
                                biff/base-url
                                biff/send-email
                                template
                                location]
                         :as env}]
  (let [{:keys [params] :as env} (update env :params
                                   (fn [m] (bu/map-vals
                                             #(if (coll? %)
                                                (some not-empty %)
                                                %)
                                             m)))
        link (signin-link (assoc env
                            :claims params
                            :url (str base-url "/api/signin")))]
    (send-email (merge env
                  {:to email
                   :template template
                   :data {:biff.auth/link link}})))
  {:status 302
   :headers/Location (str (uri/join base-url location))})

(defn signin [{:keys [params/token session biff/base-url biff/db biff/node biff.handler/secure-defaults]
               :biff.auth/keys [on-signin on-signin-fail]
               :as env}]
  (if-some [{:keys [email] :as claims}
            (-> token
              (token/decode {:secret (jwt-key env)
                             :alg :HS256})
              bu/catchall)]
    (let [new-user-ref {:user/id (java.util.UUID/randomUUID)}
          user (merge
                 {:crux.db/id new-user-ref
                  :user/email email}
                 new-user-ref
                 (ffirst
                   (crux/q db
                     {:find '[e]
                      :args [{'input-email email}]
                      :where '[[e :user/email email]
                               [(biff.auth/email= email input-email)]]
                      :full-results? true}))
                 (bu/assoc-some
                   {:last-signed-in (java.util.Date.)}
                   :claims (not-empty (dissoc claims :email :iss :iat :exp))))]
      (crux/submit-tx node [[:crux.tx/put user]])
      {:status 302
       :headers/Location (str (uri/join base-url on-signin))
       :cookies/csrf {:path "/"
                      :max-age (* 60 60 24 90)
                      :same-site :lax
                      :value (force anti-forgery/*anti-forgery-token*)}
       ; todo figure out why this is necessary. We already set :lax in biff.http.
       :session-cookie-attrs {:path "/"
                              :http-only true
                              :same-site :lax
                              :secure (boolean secure-defaults)
                              :max-age (* 60 60 24 90)}
       :session (assoc session :uid (:user/id user))})
    {:status 302
     :headers/Location (str (uri/join base-url on-signin-fail))}))

(defn signout [{:keys [biff.auth/on-signout biff/base-url]}]
  {:status 302
   :headers/Location (str (uri/join base-url on-signout))
   :cookies/ring-session {:value "" :max-age 0}
   :cookies/csrf {:value "" :max-age 0}
   :session nil})

(defn signed-in? [req]
  {:status (if (-> req :session/uid some?)
             200
             403)})

(defn route [{:biff.auth/keys [on-signup on-signin-request]}]
  ["/api"
   ["/signup" {:post #(send-signin-link (assoc %
                                          :template :biff.auth/signup
                                          :location on-signup))
               :name ::signup}]
   ["/signin-request" {:post #(send-signin-link (assoc %
                                                  :template :biff.auth/signin
                                                  :location on-signin-request))
                       :name ::signin-request}]
   ["/signin" {:get #(signin %)
               :name ::signin
               :middleware [anti-forgery/wrap-anti-forgery]}]
   ["/signout" {:get #(signout %)
                :name ::signout}]
   ["/signed-in" {:get #(signed-in? %)
                  :name ::signed-in}]])
