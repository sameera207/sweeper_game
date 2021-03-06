(ns sweepergame.server
  (:use [noir.core]
   [noir.request]
   [noir.response :only [redirect]]
   [sweepergame.core]
   [hiccup.page-helpers :only [html5 link-to  include-js]]
   [hiccup.form-helpers]
   [cheshire.core :only [generate-string]]
   )
  (:require [noir.server :as server]))

(def status (ref {:numplayers 0 :players {}}))
(def debug false)
(def hintsleep 400)
(def opensleep 150)

(defn player-object [player-no]
  ((@status :players) (str "" player-no)))


(def tiles-to-open (count (filter #(= 0 %) (reduce concat (gen-new-board)))))

(defn sort-by-score [player-values]
  (sort-by :points (fn [val1 val2] (* -1 (compare (val1 :total) (val2 :total))))
       player-values)
  )


(defn html-encode [x]
  (-> x
    (.replaceAll "&" "&amp;")
    (.replaceAll ">" "&gt;")
    (.replaceAll "<" "&lt;")
    (.replaceAll "æ" "&aelig;")
    (.replaceAll "Æ" "&Aelig;")
    (.replaceAll "ø" "&oslash;")
    (.replaceAll "Ø" "&Oslash;")
    (.replaceAll "å" "&aring;")
    (.replaceAll "Å" "&Åring;")

  ))


(defn new-playno [playno]
  (if debug playno
  (+ (* (+ (rand-int 8000) 1000) 100) (+ playno 10)))
  )

(defn register-new-player [new-player-name]
  (dosync
   (let [playno (new-playno (inc (@status :numplayers)))]

     (ref-set status (assoc @status
                       :numplayers (inc (@status :numplayers))
                       :players (assoc (@status :players)
                                  (str playno)
                                  {:name new-player-name :board (gen-new-board) :points {:total 0 :finishedBoards 0 :maxOnBoard 0 :bombed 0}})))
     playno
     )
   )

)

(defpage [:post "/register"] {:as registerobject}
  (let [playno (register-new-player (registerobject :name))]
   (html5 [:body [:h1 "You have code " playno]
             [:p (link-to "/" "Scoreboard")]]))
)




(defn board-str [board]
  (str "<html></body><table>"
  (reduce str
    (map (fn [row]
      (str "<tr>" (reduce str
        (map #(str "<td>" (cond (= :bomb %) "X" (= :open %) "1" :else "0") "</td>") row))
       "</tr>")
   ) board))
   "</table></body></html>")
  )





(defn to-int [s]
  (try (Integer/parseInt s) (catch NumberFormatException e nil)))

(defn read-coordinates [para]
  (if (nil? para) nil
  (let [x (if (nil? (para :x)) (para :X) (para :x))
        y (if (nil? (para :y)) (para :Y) (para :y))]
  (if (or (nil? (to-int x)) (nil? (to-int y)))
    nil
    [(to-int y) (to-int x)]
  ))))

(defn in-third [x]
  (* x x x)
  )

(def tiles-to-open (- (* board-rows board-cols) board-bombs))
(def max-score (in-third tiles-to-open))

(defn calc-score [type-of-open open-res board old-score]
  (if (or (= open-res :open) (= open-res :bomb)) (assoc old-score :total 0 :bombed (inc (old-score :bombed)))
  (let [move-score
    (if (= :open type-of-open) (let [num-open (number-of-opens board)]
     (- (in-third num-open) (if (> num-open 0) (in-third (dec num-open)) 0))
    ) 0)]
  (assoc old-score :total (+ (old-score :total) move-score)
   :finishedBoards (if (finished? board) (inc (old-score :finishedBoards)) (old-score :finishedBoards))
   :maxOnBoard (max (old-score :maxOnBoard) (number-of-opens board)))
   ))
)

(defpage [:get "/open"] {:as openpart}
  (let [player-map ((@status :players) (openpart :id)) pos (read-coordinates openpart)]
  (cond
    (nil? player-map) "Unknown player"
    (nil? pos) "Coordinates needed"
    :else (let [result (open pos (player-map :board))]
  (let [score (calc-score :open (result :result) (result :board) (player-map :points))]
    (dosync (ref-set status (assoc @status
      :players (assoc (@status :players)
    (openpart :id) (assoc player-map :points score :board (replace-if-finished result))))))
    (Thread/sleep opensleep)
    (str "Y=" (first (result :pos)) ",X=" (second (result :pos)) ",result=" (result :result))
  ))
  )
))

(defn update-player [result player-no player-map]
  (dosync (ref-set status (assoc @status
                          :players (assoc (@status :players)
                                     player-no (assoc player-map :board (replace-if-finished result)
                                                      :points (calc-score :hint (result :result) (result :board) (player-map :points))))))
        ))


(defpage [:get "/hint"] {:as openpart}
  (let [player-map ((@status :players) (openpart :id))]
  (if
    (nil? player-map) "Unknown player"
    (let [result (hint (player-map :board))]
    (update-player result (openpart :id) player-map)
    (Thread/sleep hintsleep)
    (str "Y=" (first (result :result)) ",X=" (second (result :result)) ",result=" (result :count))
  )))
  )

(defn board-as-html [board]
  [:table
  (map (fn [row] [:tr (map (fn [cell] [:td
    (cond (= :bomb cell) (if debug "X" "0")
          (= :open cell) "1"
          (= :hint cell) "h"
          :else "0")]) row)]) board)
  ]
)

(defpage [:get "/debugdisplay"] {:as idpart}
  (html5
    [:head
    [:title "Sweepergame"]
    (include-js "/jquery-1.7.2.js") (include-js "/reload.js")]
    [:body
    (let [player-map ((@status :players) (idpart :id))]
    (if (nil? player-map) "Unknown player"
    (let [player-board (player-map :board) player-score (player-map :points)]
      (board-as-html (player-map :board))
    )))
    ]
))

(defpage [:get "/"] {:as nopart}
  (redirect "/index.html")
)

(defn gen-json-score [scores]
  (generate-string (map (fn [val]
                          {:name (val :name)
                           :total ((val :points) :total)
                           :finishedBoards ((val :points) :finishedBoards)
                           :maxOnBoard ((val :points) :maxOnBoard)
                           :bombed ((val :points) :bombed)}
                          ) scores)
                   )
)

(defpage [:get "/scorejson"] {:as nopart}
  (gen-json-score (sort-by-score (vals (@status :players))))
)

(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "1337"))
        ]
    (server/start port {:mode mode
                        :ns 'sweepergame})))