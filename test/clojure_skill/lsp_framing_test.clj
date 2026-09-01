(ns clojure-skill.lsp-framing-test
  "LSP message framing. Content-Length counts bytes, so a message with non-ASCII
  text is where a char-based reader silently desynchronises the stream and every
  later request times out."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-skill.lsp-bridge :as bridge])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(defn- framed
  "Wire bytes for the given JSON bodies, framed the way an LSP server sends them."
  [& bodies]
  (ByteArrayInputStream.
   (.toByteArray
    (reduce (fn [^java.io.ByteArrayOutputStream out ^String body]
              (let [bytes (.getBytes body StandardCharsets/UTF_8)]
                (.write out (.getBytes (str "Content-Length: " (alength bytes) "\r\n\r\n")
                                       StandardCharsets/UTF_8))
                (.write out bytes)
                out))
            (java.io.ByteArrayOutputStream.)
            bodies))))

(deftest an-ascii-message-is-read
  (is (= {:id 1 :result "ok"}
         (bridge/read-lsp-message (framed "{\"id\":1,\"result\":\"ok\"}")))))

(deftest a-message-containing-non-ascii-does-not-desynchronise-the-stream
  (testing "the second message must still be readable after a multi-byte first one"
    (let [in (framed "{\"id\":1,\"result\":\"表紙に図があるマニュアル\"}"
                     "{\"id\":2,\"result\":\"next\"}")]
      (is (= {:id 1 :result "表紙に図があるマニュアル"} (bridge/read-lsp-message in)))
      (is (= {:id 2 :result "next"} (bridge/read-lsp-message in))))))

(deftest several-messages-are-read-in-order
  (let [in (apply framed (for [i (range 5)] (str "{\"id\":" i ",\"result\":\"日本語" i "\"}")))]
    (is (= (range 5) (map (fn [_] (:id (bridge/read-lsp-message in))) (range 5))))))

(deftest a-closed-stream-is-end-of-input-not-a-bad-message
  (testing "the reader loop retries a bad message but gives up on a dead server"
    (is (= :clojure-skill.lsp-bridge/eof
           (bridge/read-lsp-message (ByteArrayInputStream. (byte-array 0)))))))

(deftest a-body-that-is-not-json-is-one-bad-message
  (is (= :clojure-skill.lsp-bridge/malformed
         (bridge/read-lsp-message (framed "not json at all")))))

(deftest a-truncated-body-is-reported-rather-than-returned-half-read
  (let [in (ByteArrayInputStream.
            (.getBytes "Content-Length: 100\r\n\r\n{\"id\":1}" StandardCharsets/UTF_8))]
    (is (= :clojure-skill.lsp-bridge/malformed (bridge/read-lsp-message in)))))

(deftest the-content-length-header-is-matched-case-insensitively
  (let [in (ByteArrayInputStream.
            (.getBytes "content-length: 8\r\n\r\n{\"id\":1}" StandardCharsets/UTF_8))]
    (is (= {:id 1} (bridge/read-lsp-message in)))))
