(ns sweepergame.server
  (:use [noir.core]
   [noir.request]
   [noir.response :only [redirect]]
   [sweepergame.core]
   [hiccup.page-helpers :only [html5 link-to]]
   [hiccup.form-helpers])
  (:require [noir.server :as server]))

(def status (ref {:numplayers 0 :players {}}))
(def debug true)


(def tiles-to-open (count (filter #(= 0 %) (reduce concat (gen-new-board)))))

(def instructions
  [:div [:h2 "Instructions"]
    [:p (str "You must solve a minesweeper game with " board-rows " rows and " board-cols
      " columns with " board-bombs " bombs.")]
    [:p "You can use the following commands"]
    [:ul [:li "To open a cell : http://&lt;server-address&gt;/open?id=&lt;your id&gt;&amp;x=&lt;x (column no starting on 0)&gt;&amp;y=&lt;y (row)&gt;"]
     [:li "Let the server open a cell for you - http://&lt;server-address&gt;/open?id=&lt;your id&gt;&amp;x=&lt;x (column no starting on 0)&gt;&amp;y=&lt;y (row)&gt;"]
    ]
    [:p "You will get points for each finished board. Fewer hints used means more points. If you open a mine or a cell that is already opened your score will be set to zero"]
    [:p "When you finish a board (or a mine blows up) a new board will be generated automatically"]  
    ]
  )

(defn  show-scoreboard []
  [:div {:id "scoreboard"}
    [:table {:border 1}
      [:tr [:th "Name"] [:th "Score"]]
      (map (fn [player-map] [:tr [:td (player-map :name)] [:td ((player-map :points) :total)]]) (vals (@status :players)))
      ]
    instructions
    ]
  )

(defpage "/" []
    (html5 [:body [:h1 "Welcome to Sweepergame"]
    (form-to [:post "/register"]
     (label "newval" "Your name")
     (text-field "name")
     (submit-button "Register"))
    [:p (show-scoreboard)]])
)

(defn new-playno [playno]
  (if debug playno
  (+ (* (+ (rand-int 8000) 1000) 100) (+ playno 10)))
  )

(defpage [:post "/register"] {:as registerobject}
  (dosync 
    (let [playno (new-playno (inc (@status :numplayers)))]
    (ref-set status (assoc @status 
      :numplayers (inc (@status :numplayers))
      :players (assoc (@status :players)
      (str playno)
      {:name (registerobject :name) :board (gen-new-board) :points {:total 0}})))
    (html5 [:body [:h1 "You have code " playno]
           [:p (link-to "/" "Scoreboard")]]))
  )  
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
  (if (or (nil? para) (nil? (to-int (para :x))) (nil? (to-int (para :y))))
    nil
    [(to-int (para :y)) (to-int (para :x))]
  ))

(defn in-third [x]
  (* x x x)
  )

(def tiles-to-open (- (* board-rows board-cols) board-bombs))
(def max-score (in-third tiles-to-open))

(defn calc-score [open-res board old-score]  
  (cond (or (= open-res :open) (= open-res :bomb)) {:total 0}
  (finished? board) {:total 
      (+ old-score (- max-score (in-third (- tiles-to-open (number-of-hints board)))))}
  :else old-score
  )
  )

(defpage [:get "/open"] {:as openpart}
  (let [player-map ((@status :players) (openpart :id)) pos (read-coordinates openpart)]
  (cond 
    (nil? player-map) "Unknown player"
    (nil? pos) "Coordinates needed"
    :else (let [result (open pos (player-map :board))]
  (let [score (calc-score (result :result) (result :board) (player-map :points))]
    (dosync (ref-set status (assoc @status 
      :players (assoc (@status :players) 
    (openpart :id) (assoc player-map :points score :board (replace-if-finished result))))))
    (str "Y=" (first (result :pos)) ",X=" (second (result :pos)) ",result=" (result :result))
  ))
  )
))

(defpage [:get "/hint"] {:as openpart}
  (let [player-map ((@status :players) (openpart :id))]
  (if 
    (nil? player-map) "Unknown player"
    (let [result (hint (player-map :board))]
    (dosync (ref-set status (assoc @status 
      :players (assoc (@status :players) 
    (openpart :id) (assoc player-map :board (replace-if-finished result) :points (calc-score (result :result) (result :board) (player-map :points)))))))
    (str "Y=" (first (result :result)) ",X=" (second (result :result)) ",result=" (result :count))
  )))
  )

(defn board-as-html [board]
  [:table
  (map (fn [row] [:tr (map (fn [cell] [:td 
    (cond (= :bomb cell) "X" 
          (= :open cell) "1" 
          (= :hint cell) "h"
          :else "0")]) row)]) board)
  ]
)

(defpage [:get "/debugdisplay"] {:as idpart}
  (html5 [:body
  (if debug 
    (let [player-map ((@status :players) (idpart :id))]
    (if (nil? player-map) "Unknown player"
    (let [player-board (player-map :board) player-score (player-map :points)]
      (board-as-html (player-map :board))
    )))
    "Only allowed in debug mode")]
))

(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "1337"))
        ]
    (server/start port {:mode mode
                        :ns 'sweepergame})))