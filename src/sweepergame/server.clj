(ns sweepergame.server
  (:use noir.core)
  (:use noir.request)
  (:use sweepergame.core)
  (:require [noir.server :as server]))

(def status (ref {}))
(def debug true)

(defpage "/" []
    "Welcome to Noiraazz")

(defpage "/open" []
  (str ((ring-request) :params))
  )

(defn board-str [board]
  (str "<html></body><table>"
  (reduce str 
    (map (fn [row] 
      (str "<tr>" (reduce str 
        (map #(str "<td>" (if (= :bomb %) "X" "0") "</td>") row)) 
       "</tr>")
   ) board)) 
   "</table></body></html>")
  )


(defn update-board [board]
  (dosync (ref-set status (assoc @status :board board)))
  )

(defpage "/new" []
  (update-board (random-board 8 8 10))
  (board-str (@status :board))
  )

(defpage "/show" []
  (board-str (@status :board))
  )

(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "1337"))
        ]
    (server/start port {:mode mode
                        :ns 'sweepergame})))