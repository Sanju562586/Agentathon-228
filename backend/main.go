package main

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/guardian/mesh-backend/crypto"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
	"golang.org/x/crypto/bcrypt"
)

// --- Configuration ---
// No more dbFile const. We use MONGO_URI from env.

// MASTER_KEY for AES Encryption (In prod, use Vault/Env Var)
var MASTER_KEY = []byte("01234567890123456789012345678901")

// --- Data Structures ---

type User struct {
	Email              string `json:"email" bson:"email"`
	PasswordHash       string `json:"passwordHash" bson:"passwordHash"` // secure
	Mobile             string `json:"mobile" bson:"mobile"`
	PublicKey          string `json:"publicKey" bson:"publicKey"`
	EncryptedFaceData  string `json:"encryptedFaceData" bson:"encryptedFaceData"` // AES
	Signature          string `json:"signature" bson:"signature"`
	EncryptedEmbedding string `json:"encryptedEmbedding" bson:"encryptedEmbedding"` // AES(json([]float64))
}

type LoginRequest struct {
	Email         string    `json:"email"`
	Password      string    `json:"password"`
	Signature     string    `json:"signature"`
	Challenge     string    `json:"challenge"`
	FaceEmbedding []float64 `json:"faceEmbedding"` // Live embedding from device
	PublicKey     string    `json:"publicKey"`     // Device's Public Key (for rotation)
}

type ChallengeResponse struct {
	Challenge string `json:"challenge"`
}

type VerifyRequest struct {
	DeviceID  string  `json:"deviceId"`
	RiskScore float64 `json:"riskScore"`
	Signature string  `json:"signature"`
	Challenge string  `json:"challenge"`
	PublicKey string  `json:"publicKey"`
}

var (
	// users map removed in favor of MongoDB
	userCollection *mongo.Collection
)

func main() {
	fmt.Println("Starting Guardian Mesh Gatekeeper (CLOUD/MONGO MODE)...")

	// 1. Initialize Database
	initDB()
	startStatusMonitor()

	// 2. HTTP Handlers
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "Guardian Gatekeeper is Online (Mongo Connected)")
	})

	http.HandleFunc("/auth/challenge", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		challenge, err := crypto.GenerateChallenge()
		if err != nil {
			http.Error(w, "Failed to generate challenge", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(ChallengeResponse{Challenge: challenge})
	})

	http.HandleFunc("/auth/verify", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var req VerifyRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "Invalid request", http.StatusBadRequest)
			return
		}

		// TRUST SCORE (0.0 to 1.0, Higher is Better)
		trustScore := req.RiskScore

		log.Printf("Received verification request for DeviceID: %s with Trust Score: %.2f", req.DeviceID, trustScore)

		if trustScore < 0.5 { // Threshold for Trust
			log.Printf("Trust Score too low (%.2f < 0.5). Auth Denied.", trustScore)
			http.Error(w, "Trust Score too low. Auth Denied.", http.StatusForbidden)
			return
		}

		// For verification endpoint, we just say successful if trust is high
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("Verified"))
	})

	http.HandleFunc("/register", registerHandler)
	http.HandleFunc("/auth/login", loginHandler)

	// --- Cloud Broker Endpoints ---
	http.HandleFunc("/agent/request", agentRequestHandler)
	http.HandleFunc("/agent/poll", agentPollHandler)
	http.HandleFunc("/agent/pending", agentPendingHandler)
	http.HandleFunc("/agent/respond", agentRespondHandler)
	http.HandleFunc("/agent/alert", agentAlertHandler)

	// 3. Start Server
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
		log.Println("PORT not set, defaulting to 8080")
	}

	log.Printf("Listening on :%s", port)
	if err := http.ListenAndServe(":"+port, nil); err != nil {
		log.Fatal(err)
	}
}

// --- Database Init ---
func initDB() {
	uri := os.Getenv("MONGO_URI")
	if uri == "" {
		log.Println("⚠️ WARNING: MONGO_URI is not set. Database operations will fail or panic.")
		log.Println("Please set MONGO_URI in your environment variables.")
		// For local dev convenience without crash, we could default to local, but explicit is better for cloud.
		// We'll proceed but operations will largely fail if not set.
	}

	// Connect to MongoDB
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	client, err := mongo.Connect(ctx, options.Client().ApplyURI(uri))
	if err != nil {
		log.Fatalf("❌ Failed to create Mongo client: %v", err)
	}

	// Ping the database
	err = client.Ping(ctx, nil)
	if err != nil {
		log.Fatalf("❌ Failed to verify Mongo connection: %v", err)
	}

	log.Println("✅ Connected to MongoDB Atlas successfully!")
	userCollection = client.Database("guardian_mesh").Collection("users")
}

// --- Handlers ---

func registerHandler(w http.ResponseWriter, r *http.Request) {
	log.Println("⚡ REGISTER HANDLER (MONGO)")
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// Temporary struct to decode request including Plaintext fields
	type RegisterRequest struct {
		Email         string    `json:"email"`
		Password      string    `json:"password"`
		Mobile        string    `json:"mobile"`
		PublicKey     string    `json:"publicKey"`
		FaceData      string    `json:"faceData"` // Plain Base64 from App
		Signature     string    `json:"signature"`
		FaceEmbedding []float64 `json:"faceEmbedding"` // Plain vector
	}

	var req RegisterRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	// 1. Hash Password
	hash, err := hashPassword(req.Password)
	if err != nil {
		http.Error(w, "Password hashing failed", http.StatusInternalServerError)
		return
	}

	// 2. Encrypt Face Data
	encFaceData, err := encrypt(req.FaceData)
	if err != nil {
		http.Error(w, "Encryption failed", http.StatusInternalServerError)
		return
	}

	// 3. Encrypt Embedding
	embBytes, _ := json.Marshal(req.FaceEmbedding)
	encEmbedding, err := encrypt(string(embBytes))
	if err != nil {
		http.Error(w, "Embedding encryption failed", http.StatusInternalServerError)
		return
	}

	// Store
	user := User{
		Email:              req.Email,
		PasswordHash:       hash, // Stored Hash
		Mobile:             req.Mobile,
		PublicKey:          req.PublicKey,
		EncryptedFaceData:  encFaceData, // Stored Encrypted
		Signature:          req.Signature,
		EncryptedEmbedding: encEmbedding, // Stored Encrypted
	}

	// Upsert into MongoDB
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	filter := bson.M{"email": req.Email}
	update := bson.M{"$set": user}
	optimistic := true
	opts := options.UpdateOptions{Upsert: &optimistic}

	_, err = userCollection.UpdateOne(ctx, filter, update, &opts)
	if err != nil {
		log.Printf("Mongo Update Failed: %v", err)
		http.Error(w, "Database Error", http.StatusInternalServerError)
		return
	}

	fmt.Printf("Registered User: %s (Secure/Mongo)\n", req.Email)
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{"message": "Registration successful"})
}

func loginHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var loginReq LoginRequest
	if err := json.NewDecoder(r.Body).Decode(&loginReq); err != nil {
		http.Error(w, "Invalid request", http.StatusBadRequest)
		return
	}

	// Fetch User from MongoDB
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var user User
	err := userCollection.FindOne(ctx, bson.M{"email": loginReq.Email}).Decode(&user)
	if err != nil {
		if err == mongo.ErrNoDocuments {
			http.Error(w, "User not found", http.StatusUnauthorized)
		} else {
			log.Printf("Mongo Find Error: %v", err)
			http.Error(w, "Database Error", http.StatusInternalServerError)
		}
		return
	}

	// 1. Verify Password Hash
	if !checkPasswordHash(loginReq.Password, user.PasswordHash) {
		fmt.Println("Password mismatch")
		http.Error(w, "Invalid Password", http.StatusUnauthorized)
		return
	}

	// 2. Verify Signature
	pubKeyToVerify := loginReq.PublicKey
	if pubKeyToVerify == "" {
		pubKeyToVerify = user.PublicKey
	}

	validSig, err := crypto.VerifySignature(pubKeyToVerify, loginReq.Challenge, loginReq.Signature)
	if err != nil || !validSig {
		fmt.Printf("Signature Invalid. Err: %v\n", err)
		http.Error(w, "Invalid Signature/Key", http.StatusUnauthorized)
		return
	}

	// 3. Verify Face (Decrypt -> Metric -> Compare)
	decEmbStr, err := decrypt(user.EncryptedEmbedding)
	if err != nil {
		fmt.Printf("Decryption Error: %v\n", err)
		http.Error(w, "Server Data Corruption", http.StatusInternalServerError)
		return
	}

	var storedEmbedding []float64
	json.Unmarshal([]byte(decEmbStr), &storedEmbedding)

	distance := euclideanDistance(storedEmbedding, loginReq.FaceEmbedding)
	fmt.Printf("User: %s, Face Distance: %f\n", loginReq.Email, distance)

	if distance > 1.1 {
		http.Error(w, fmt.Sprintf("Face Verification Failed. Distance: %.3f", distance), http.StatusUnauthorized)
		return
	}

	// --- AUTH SUCCEEDED ---

	// 4. Multi-Device Logic: Update Public Key if different
	if loginReq.PublicKey != "" && loginReq.PublicKey != user.PublicKey {
		fmt.Printf("Rotating Public Key for user %s to new device key.\n", user.Email)
		
		_, err := userCollection.UpdateOne(ctx, 
			bson.M{"email": user.Email},
			bson.M{"$set": bson.M{"publicKey": loginReq.PublicKey}},
		)
		if err != nil {
			log.Printf("Failed to update Public Key: %v", err)
			// Non-critical, continue
		}
	}

	fmt.Printf("User %s logged in successfully.\n", loginReq.Email)
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{"status": "logged_in", "distance": fmt.Sprintf("%f", distance)})
}

// --- Helpers ---

func hashPassword(password string) (string, error) {
	bytes, err := bcrypt.GenerateFromPassword([]byte(password), 14)
	return string(bytes), err
}

func checkPasswordHash(password, hash string) bool {
	err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(password))
	return err == nil
}

func encrypt(plaintext string) (string, error) {
	block, err := aes.NewCipher(MASTER_KEY)
	if err != nil {
		return "", err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err = io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}

	ciphertext := gcm.Seal(nonce, nonce, []byte(plaintext), nil)
	return hex.EncodeToString(ciphertext), nil
}

func decrypt(encryptedHex string) (string, error) {
	data, err := hex.DecodeString(encryptedHex)
	if err != nil {
		return "", err
	}

	block, err := aes.NewCipher(MASTER_KEY)
	if err != nil {
		return "", err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}

	nonceSize := gcm.NonceSize()
	if len(data) < nonceSize {
		return "", fmt.Errorf("ciphertext too short")
	}

	nonce, ciphertext := data[:nonceSize], data[nonceSize:]
	plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return "", err
	}

	return string(plaintext), nil
}

func euclideanDistance(a, b []float64) float64 {
	if len(a) != len(b) || len(a) == 0 {
		return 999.0
	}
	var sum float64
	for i := range a {
		diff := a[i] - b[i]
		sum += diff * diff
	}
	return math.Sqrt(sum)
}

// --- Cloud Broker Logic ---

var (
	// Map[RequestID]RequestData
	agentRequests = make(map[string]AgentRequest)
	// Map[RequestID]ResponseData
	agentResponses = make(map[string]AgentResponse)
	brokerMutex    = &sync.Mutex{}
)

type AgentRequest struct {
	RequestID string `json:"requestId"`
	Service   string `json:"service"` // e.g. "gmail", "netflix"
	Source    string `json:"source"`  // e.g. "laptop_1"
	Status    string `json:"status"`  // "pending", "fulfilled"
	Timestamp int64  `json:"timestamp"`
	PublicKey string `json:"publicKey"` // Device's Public Key for E2EE
}

type AgentResponse struct {
	RequestID   string `json:"requestId"`
	Credentials string `json:"credentials"` // Encrypted ideally, simple string for simulation
}

func agentRequestHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req AgentRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid Body", http.StatusBadRequest)
		return
	}
	req.Status = "pending"

	brokerMutex.Lock()
	agentRequests[req.RequestID] = req
	brokerMutex.Unlock()

	log.Printf("☁️ Broker: New Request from %s for %s (ID: %s)\n", req.Source, req.Service, req.RequestID)
	w.WriteHeader(http.StatusOK)
}

func agentPendingHandler(w http.ResponseWriter, r *http.Request) {
	// Mobile App calls this to see if anyone needs help
	brokerMutex.Lock()
	defer brokerMutex.Unlock()

	// Initialize as empty slice so we send [] instead of null
	pending := make([]AgentRequest, 0)
	
	for _, req := range agentRequests {
		if req.Status == "pending" {
			pending = append(pending, req)
		}
	}
	
	// Verbose Heartbeat: Proves the Phone is listening
	log.Printf("💓 Heartbeat: Phone checked for work via %s. Pending: %d\n", r.RemoteAddr, len(pending))
	lastDeviceContact.Store(time.Now())
	
	if len(pending) > 0 {
		log.Printf("📱 Android: Sending %d pending requests to agent\n", len(pending))
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(pending)
}

func agentRespondHandler(w http.ResponseWriter, r *http.Request) {
	// Mobile App calls this to PROVIDE the password
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var resp AgentResponse
	if err := json.NewDecoder(r.Body).Decode(&resp); err != nil {
		http.Error(w, "Invalid Body", http.StatusBadRequest)
		return
	}

	brokerMutex.Lock()
	if _, exists := agentRequests[resp.RequestID]; exists {
		agentResponses[resp.RequestID] = resp
		// Update status
		req := agentRequests[resp.RequestID]
		req.Status = "fulfilled"
		agentRequests[resp.RequestID] = req
		log.Printf("☁️ Broker: Received Response for %s\n", resp.RequestID)
	} else {
		log.Printf("⚠️ WARNING: Received response for UNKNOWN Request ID: %s\n", resp.RequestID)
	}
	brokerMutex.Unlock()

	w.WriteHeader(http.StatusOK)
}

func agentPollHandler(w http.ResponseWriter, r *http.Request) {
	// Laptop calls this to wait for the password
	// Enable CORS
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type")

	if r.Method == "OPTIONS" {
		w.WriteHeader(http.StatusOK)
		return
	}

	id := r.URL.Query().Get("requestId")
	if id == "" {
		http.Error(w, "Missing requestId", http.StatusBadRequest)
		return
	}

	brokerMutex.Lock()
	resp, exists := agentResponses[id]
	brokerMutex.Unlock()

	if exists {
		log.Printf("☁️ Broker: Serving Response to Poll for %s\n", id)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(resp)
	} else {
		// Rate limit "Poll miss" logs to avoid spamming the user
		if val, ok := pollLogLimiter.LoadOrStore(id, int64(0)); ok {
			last := val.(int64)
			now := time.Now().Unix()
			if now-last > 5 { // Log only every 5 seconds per ID
				log.Printf("☁️ Broker: Poll miss for %s (Not Ready)\n", id)
				pollLogLimiter.Store(id, now)
			}
		} else {
			log.Printf("☁️ Broker: Poll miss for %s (Waiting for Phone...)\n", id)
			pollLogLimiter.Store(id, time.Now().Unix())
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

var pollLogLimiter = sync.Map{} // Minimal rate limiter
var lastDeviceContact atomic.Value // Stores time.Time

func startStatusMonitor() {
	ticker := time.NewTicker(10 * time.Second)
	go func() {
		for range ticker.C {
			count := 0
			brokerMutex.Lock()
			count = len(agentRequests)
			brokerMutex.Unlock()
			
			lastContact := "Never"
			if t, ok := lastDeviceContact.Load().(time.Time); ok {
				since := time.Since(t).Seconds()
				lastContact = fmt.Sprintf("%.0fs ago", since)
				if since > 10 {
					lastContact += " (⚠️ DEVICE OFFLINE?)"
				} else {
					lastContact += " (✅ ONLINE)"
				}
			}
			
			log.Printf("---- [SYSTEM STATUS] Pending Req: %d | Last Phone Heartbeat: %s ----", count, lastContact)
		}
	}()
}

func agentAlertHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var alert map[string]interface{}
	if err := json.NewDecoder(r.Body).Decode(&alert); err != nil {
		http.Error(w, "Invalid Body", http.StatusBadRequest)
		return
	}

	log.Printf("🚨 ALERT RECEIVED: %v\n", alert)
	w.WriteHeader(http.StatusOK)
}
