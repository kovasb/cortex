(ns cortex.impl.layers
  (:require [cortex.protocols :as cp]
            [cortex.util :as util :refer [error EMPTY-VECTOR]]
            [clojure.core.matrix :as m]
            [core.blas.protocols :as blas]
            [clojure.core.matrix.protocols :as mp]
            [cortex.backends :as b]
            [cortex.util :as util]
            #?(:clj [cortex.impl.vectorz-blas])
            #?(:clj [cortex.registry :refer [register-module]]
               :cljs [cortex.registry :refer-macros [register-module]]))
  #?(:clj (:import [java.util PriorityQueue]
                   [cortex.impl ConvOps])))

#?(:clj (do
          (set! *warn-on-reflection* true)
          (set! *unchecked-math* :warn-on-boxed)))

;; LOGISTIC
;; Module implementing a Logistic activation function over a numerical array
#?(:cljs (register-module cortex.impl.layers.Logistic))
(defrecord Logistic [output input-gradient]
  cp/PModule
    (calc [this input]
      (m/assign! output input)
      (m/logistic! output)
      this)

    (output [this]
      (:output this))

  cp/PNeuralTraining
    (forward [this input]
      (cp/calc this input))

    (backward [this input output-gradient]
      (let []
        ;; input gradient = output * (1 - output) * output-gradient
        (m/assign! input-gradient 1.0)
        (m/sub! input-gradient output)
        (m/mul! input-gradient output output-gradient)

        ;; finally return this, input-gradient has been updated in-place
        this))

    (input-gradient [this]
      input-gradient))


;; DROPOUT
;; Module implementing "dropout" functionality when training
;; dropout field stores 0.0 or 1.0/probability as multiplicative noise
;; Works as a identity function otherwise
#?(:cljs (register-module cortex.impl.layers.Dropout))
(defrecord Dropout [output input-gradient probability dropout]
  cp/PModule
    (calc [this input]
      (m/assign! output input)
      this)

    (output [this]
      (:output this))

  cp/PNeuralTraining
    (forward [this input]
      (let [probability (double probability)
            inv-prob (double (/ 1.0 probability))]
        (m/emap! (fn ^double [^double _] (if (< (Math/random) probability) inv-prob 0.0)) dropout))
      (m/assign! output input)
      (m/mul! output dropout)
      this)

    (backward [this input output-gradient]
      (let []
        (m/assign! input-gradient output-gradient)
        (m/mul! input-gradient dropout)
        this))

    (input-gradient [this]
      input-gradient))


;; SCALE
;; Module implementing simple scaling functionality and addition with a constant
;; - factor of nil works as identity
;; - constant of nil works as identity
#?(:cljs (register-module cortex.impl.layers.Scale))
(defrecord Scale [output input-gradient factor constant]
  cp/PModule
    (calc [this input]
      (m/assign! output input)
      (when factor (m/mul! output factor))
      (when constant (m/add! output constant))
      this)

    (output [this]
      (:output this))

  cp/PNeuralTraining
    (forward [this input]
      (m/assign! output input)
      (when factor (m/mul! output factor))
      (when constant (m/add! output constant))
      this)

    (backward [this input output-gradient]
      (let []
        (m/assign! input-gradient output-gradient)
        (when factor (m/mul! input-gradient factor))
        this))

    (input-gradient [this]
      input-gradient))


;;There is an option that torch uses which is if the input is less than 0
;;then multiply it by a special value (negval).
;;https://github.com/torch/nn/blob/master/lib/THNN/generic/LeakyReLU.c
#?(:cljs (register-module cortex.impl.layers.RectifiedLinear))
(defrecord RectifiedLinear [output input-gradient dotvec negval]
  cp/PModule
  (calc [this input]
    (m/emap! (fn ^double [^double _ ^double in] (if (neg? in) negval 1.0)) dotvec input)
    (m/assign! output input)
    (m/mul! output dotvec)
    this)

  (output [this]
    (:output this))

  cp/PNeuralTraining
  (forward [this input]
    (cp/calc this input)
    this)


  (backward [this input output-gradient]
    (m/assign! input-gradient output-gradient)
    (m/mul! input-gradient dotvec)
    this)

  (input-gradient [this]
    input-gradient))

#?(:cljs (register-module cortex.impl.layers.Tanh))
(defrecord Tanh [output input-gradient]
  cp/PModule
  (calc [this input]
    (m/assign! output input)
    (m/tanh! output)
    this)

  (output [this]
    (:output this))

  cp/PNeuralTraining
  (forward [this input]
    (cp/calc this input)
    this)

  (backward [this input output-gradient]
    (m/assign! input-gradient output-gradient)
    (m/emul! input-gradient (util/tanh' output))
    this)

  (input-gradient [this]
    input-gradient))


(defn softmax-forward!
  "Runs softmax on input and places result in output"
  [input output]
  (let [max-val (m/emax input)]
    ;;From caffe, we subtract the max for numerical stability
    ;;and then run the textbook softmax
    (m/assign! output input)
    (m/sub! output max-val)
    (m/exp! output)
    (m/div! output (m/esum output))))


(defn softmax-backward!
  ""
  [input-gradient output output-gradient input]
  (m/assign! input-gradient output-gradient))

#?(:cljs (register-module cortex.impl.layers.Softmax))
(defrecord Softmax [output input-gradient]
  cp/PModule
  (calc [this input]
    (softmax-forward! input output)
    this)

  (output [this]
    (:output this))

  cp/PNeuralTraining
  (forward [this input]
    (cp/calc this input)
    this)


  (backward [this input output-gradient]
    (softmax-backward! input-gradient (:output this) output-gradient input)
    this)

  (input-gradient [this]
    input-gradient))

;;Found through perf testing...probably needs to be
;;specified somehow through the backend
(defn blas-gemv-cutoff ^long [] 500000)

(defn linear-forward!
  "Linear backward pass.  Returns module updated with a new output."
  [this input]
  (let [weights (:weights this)
        bias (:bias this)
        elem-count (long (m/ecount weights))]
    (if (and
         (> elem-count (blas-gemv-cutoff))
         (blas/supports-blas? input)
         (blas/supports-blas? weights)
         (blas/supports-blas? bias))
      (let [output (or (:output this)
                       (b/new-array (m/shape bias)))]
        (m/assign! output bias)
        (blas/gemv! output false 1.0 weights input 1.0)
        (assoc this :output output))
      (let [output (m/inner-product weights input)]
        (m/add! output bias)
        (assoc this :output output)))))


(defn linear-backward!
  "Linear backward pass.  Returns module updated with a new input gradient."
  [this input output-gradient]
  (let [weights (:weights this)
        bias (:bias this)
        weight-gradient (:weight-gradient this)
        bias-gradient (:bias-gradient this)
        elem-count (long (m/ecount weights))]
    #?(:clj
       (if (and (mp/as-double-array weight-gradient)
                (mp/as-double-array output-gradient)
                (mp/as-double-array input))
         (ConvOps/addOuterProduct (mp/as-double-array weight-gradient)
                                  (mp/as-double-array input)
                                  (mp/as-double-array output-gradient))
         (m/add! (m/as-vector weight-gradient) (m/as-vector
                                                (m/outer-product output-gradient input))))
       :cljs (m/add! (m/as-vector weight-gradient) (m/as-vector
                                                    (m/outer-product output-gradient input))))
    (m/add! bias-gradient output-gradient)
    (if (and (blas/supports-blas? weights)
             (blas/supports-blas? output-gradient))
     (let [input-gradient (or (:input-gradient this)
                              (m/mutable (b/new-array (m/shape input))))]
       (blas/gemv! input-gradient true 1.0 weights output-gradient 0.0)
       (assoc this :input-gradient input-gradient))
     (do
       (assoc this :input-gradient (m/inner-product (m/transpose weights) output-gradient))))))

;; LINEAR
;; function that implements a linear transformation (weights + bias)
;; has mutable parameters and accumlators for gradient
#?(:cljs (register-module cortex.impl.layers.Linear))
(defrecord Linear [weights bias]
  cp/PModule
    (calc [this input]
      (linear-forward! this input))

    (output [this]
      (:output this))

  cp/PNeuralTraining
    (forward [this input]
      (-> this
        (cp/calc input)
        (assoc :input input)))

    (backward [this input output-gradient]
      (linear-backward! this input output-gradient))

    (input-gradient [this]
      (:input-gradient this))

  cp/PParameters
  (parameters [this]
    [(:weights this) (:bias this)])

  (update-parameters [this parameters]
    (let [param-view (cp/parameters this)]
      (util/assign-packed-to-sparse! param-view parameters)
      (util/zero-sparse! (cp/gradient this)))
    this)

  cp/PGradient
  (gradient [this]
    [(:weight-gradient this) (:bias-gradient this)]))

;; NORMALISER
;; Module which normalises outputs towards mean 0.0, sd 1.0
;; accumulates observed mean and variance of data, recalibrates during update-parameters
(def DEFAULT-NORMALISER-LEARN-RATE 0.001)
(def DEFAULT-NORMALISER-FACTOR 0.001)

#?(:cljs (register-module cortex.impl.layers.Normaliser))
(defrecord Normaliser [output input-gradient sd mean acc-ss acc-mean tmp]
  cp/PModule
    (calc [this input]
      (let []
        (m/assign! output input)
        (m/sub! output mean)
        (m/div! output sd)
        this))

    (output [this]
      (:output this))

  cp/PNeuralTraining
    (forward [this input]
      (let [lr (double (or (:learn-rate this) DEFAULT-NORMALISER-LEARN-RATE ))]
        (when (> lr 0)
          (let [decay (- 1.0 lr)]
            (m/scale! acc-mean decay)
            (m/add-scaled! acc-mean input lr)
            (m/scale! acc-ss decay)
            (m/add-scaled-product! acc-ss input input lr)))
        (cp/calc this input)))

    (backward [this input output-gradient]
      (let []
        ;; input gradient = output / s.d.
        (m/assign! input-gradient output-gradient)
        (m/div! input-gradient sd)

        ;; add gradient for normalisation adjustment
        (let [nf (double (or (:normaliser-factor this) DEFAULT-NORMALISER-FACTOR))]
          (when (> nf 0)
            ;; mean adjustment - gradient towards mean
            (m/assign! tmp input)
            (m/sub! tmp mean)
            (m/add-scaled! input-gradient tmp nf)

            ;; sd adjustment - gradient scales towards sd 1.0
            (m/assign! tmp sd)
            (m/sub! tmp 1.0)
            (m/add-scaled-product! input-gradient input tmp nf)
            ))

        ;; finally return this, input-gradient has been updated in-place
        this))

    (input-gradient [this]
      input-gradient)

  cp/PParameters
  (parameters
      [this]
        ;; no external parameters to optimise
        EMPTY-VECTOR)
    (update-parameters
      [this parameters]
        (m/assign! mean acc-mean)
        (m/assign! sd acc-ss)
        (m/add-scaled-product! sd mean mean -1.0)
        (m/sqrt! sd)
        this))

;; DENOISING AUTOENCODER
(defn noise-fn ^double [^double x]
  (if (< 0.2 (util/rand-normal))
    (util/rand-gaussian)
    x))

#?(:cljs (register-module cortex.impl.layers.Autoencoder))
(defrecord Autoencoder
  [up down input-tmp output-tmp ]
  cp/PModule
    (cp/calc [m input]
      (let [up (cp/calc up input)]
        (Autoencoder. up down input-tmp output-tmp)))

    (cp/output [m]
      (cp/output up))

  cp/PNeuralTraining
    (forward [this input]
      (m/assign! input-tmp input)
      ;; TODO: figure out how to apply noise
      ;; (m/emap! noise-fn input-tmp) ;; input-tmp contains input with noise
      (let [noise-up (cp/calc up input-tmp)
            _ (m/assign! output-tmp (cp/output noise-up)) ;; output-tmp contains noisy output from up
            up (cp/forward up input)
            down (cp/forward down output-tmp)
            ]
        (Autoencoder. up down input-tmp output-tmp)))

    (backward [this input output-gradient]
      (let [down (cp/backward down output-tmp (m/sub (cp/output down) input))
            _ (m/assign! output-tmp output-gradient)
            _ (m/add! output-tmp (cp/input-gradient down)) ;; output-tmp contains gradient
            up (cp/backward up input output-tmp)
            ]
        (Autoencoder. up down input-tmp output-tmp)))

    (input-gradient [this]
      (cp/input-gradient up))

    cp/PGradient
    (gradient [this]
      (concat (cp/gradient up) (cp/gradient down)))

    cp/PParameters
    (parameters [this]
      (concat (cp/parameters up) (cp/parameters down)))

    (update-parameters [this parameters]
      (let [nup (cp/parameter-count up)
            ndown (cp/parameter-count down)
            up (cp/update-parameters up (m/subvector parameters 0 nup))
            down (cp/update-parameters down (m/subvector parameters nup ndown))]
        (Autoencoder. up down input-tmp output-tmp)))

    cp/PModuleClone
      (clone [this]
        (Autoencoder. (cp/clone up)
                      (cp/clone down)
                      (m/clone input-tmp)
                      (m/clone output-tmp))))


#?(:clj
   (defrecord KSparse [^long k]
     cp/PModule
     (cp/calc [this input]
       (let [^PriorityQueue queue (or (:queue this)
                                      (PriorityQueue. k (reify java.util.Comparator
                                                          (compare ^int [this o1 o2]
                                                            (int (- (double (o1 0))
                                                                    (double (o2 0))))))))
             output (or (:output this)
                        (m/new-array :vectorz (m/shape input)))
             dotvec (or (:dotvec this)
                        (m/new-array :vectorz (m/shape input)))]
         (m/assign! dotvec 0.0)
         (m/assign! output input)
         (.clear queue)
         (doall (m/emap-indexed! (fn [idx value]
                                   (.add queue [value (first idx)])
                                   (when (> (.size queue) k)
                                     (.remove queue (.peek queue)))
                                   value)
                                 output))
         (doseq [[value idx] (seq queue)]
           (m/mset! dotvec idx 1.0))
         (m/mul! output dotvec)
         (assoc this :queue queue
                :output output
                :dotvec dotvec)))


     (cp/output [m]
       (:output m))

     cp/PNeuralTraining
     (forward [this input]
       (cp/calc this input))

     (backward [this input output-gradient]
       (let [input-gradient (or (:input-gradient this)
                                (m/new-array :vectorz (m/shape output-gradient)))]
         (m/assign! input-gradient output-gradient)
         (m/mul! input-gradient (:dotvec this))
         (assoc this :input-gradient input-gradient)))

     (input-gradient [this]
       (:input-gradient this))))


(defrecord GaussianForwardNoise []
  cp/PModule
  (calc [this input]
    (let [output (:or (:output this)
                      (b/new-array (m/shape input)))]
      (m/assign! output input)
      (m/emap! noise-fn output)
      (assoc this :output output)))
  (output [m]
    (:output m))

  cp/PNeuralTraining
  (forward [this input]
    (cp/calc this input))

  (backward [this input output-gradient]
    (let [input-gradient (or (:input-gradient this)
                             (b/new-array (m/shape output-gradient)))]
      (m/assign! input-gradient output-gradient)
      (assoc this :input-gradient input-gradient)))

  (input-gradient [this]
    (:input-gradient this)))
