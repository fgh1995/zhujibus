package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	_ "modernc.org/sqlite"
	"github.com/gorilla/websocket"
	"golang.org/x/crypto/bcrypt"
)

// ==================== 配置常量 ====================

const (
	frontendVersion   = "1.0.0" // 前端版本号，用于构建脚本读取
	defaultPort       = "8701"
	defaultAdminPassword = "admin123" // 默认管理员密码
	heartbeatTimeout  = 60 * time.Second // 心跳超时时间
	onlineBroadcastInterval = 10 * time.Second // 在线人数广播间隔
)

// ==================== 数据结构 ====================

// User 用户数据模型
type User struct {
	ID           int       `json:"id"`
	AndroidID    string    `json:"android_id"`
	Status       string    `json:"status"` // "online" 或 "offline"
	AppVersion   string    `json:"app_version"`
	FirstSeen    time.Time `json:"first_seen"`
	LastHeartbeat time.Time `json:"last_heartbeat"`
	Note         string    `json:"note"`
}

// Client WebSocket 客户端连接
type Client struct {
	Conn       *websocket.Conn
	AndroidID  string
	AppVersion string
	LastPing   time.Time
	mu         sync.Mutex
}

// VersionMessage 版本号消息格式
type VersionMessage struct {
	Type    string `json:"type"`
	Version string `json:"version"`
}

// API 响应结构
type APIResponse struct {
	Success bool   `json:"success"`
	Error   string `json:"error,omitempty"`
}

type UsersResponse struct {
	Total   int     `json:"total"`
	Online  int     `json:"online"`
	Offline int     `json:"offline"`
	Users   []User  `json:"users"`
}

// ==================== 全局变量 ====================

var (
	db           *sql.DB
	clients      = make(map[string]*Client) // android_id -> Client
	clientsMutex sync.RWMutex
	upgrader     = websocket.Upgrader{
		CheckOrigin: func(r *http.Request) bool {
			return true // 允许所有来源
		},
	}
	adminTokenHash []byte // 管理员密码哈希
)

// ==================== 数据库初始化 ====================

func initDB() error {
	var err error
	db, err = sql.Open("sqlite", "./zhujibusonline.db")
	if err != nil {
		return fmt.Errorf("打开数据库失败: %v", err)
	}

	// 创建用户表
	createTableSQL := `
	CREATE TABLE IF NOT EXISTS users (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		android_id TEXT UNIQUE NOT NULL,
		status TEXT DEFAULT 'offline',
		app_version TEXT DEFAULT '',
		first_seen DATETIME DEFAULT CURRENT_TIMESTAMP,
		last_heartbeat DATETIME DEFAULT CURRENT_TIMESTAMP,
		note TEXT DEFAULT ''
	);
	CREATE INDEX IF NOT EXISTS idx_android_id ON users(android_id);
	CREATE INDEX IF NOT EXISTS idx_status ON users(status);
	`

	_, err = db.Exec(createTableSQL)
	if err != nil {
		return fmt.Errorf("创建表失败: %v", err)
	}

	// 尝试添加 app_version 列（兼容旧数据库）
	_, _ = db.Exec("ALTER TABLE users ADD COLUMN app_version TEXT DEFAULT ''")
	// 尝试添加 note 列（兼容旧数据库）
	_, _ = db.Exec("ALTER TABLE users ADD COLUMN note TEXT DEFAULT ''")

	log.Println("数据库初始化成功")
	return nil
}

// ==================== 用户数据库操作 ====================

// registerOrGetUser 注册新用户或获取已有用户
func registerOrGetUser(androidID string) (*User, bool, error) {
	var user User
	var isNew bool

	// 先查询是否存在
	err := db.QueryRow(`
		SELECT id, android_id, status, app_version, first_seen, last_heartbeat, note
		FROM users WHERE android_id = ?
	`, androidID).Scan(&user.ID, &user.AndroidID, &user.Status, &user.AppVersion,
		&user.FirstSeen, &user.LastHeartbeat, &user.Note)

	if err == sql.ErrNoRows {
		// 新用户，插入记录（使用 UTC 时间）
		isNew = true
		now := time.Now().UTC()
		result, err := db.Exec(`
			INSERT INTO users (android_id, status, first_seen, last_heartbeat)
			VALUES (?, 'online', ?, ?)
		`, androidID, now, now)
		if err != nil {
			return nil, false, fmt.Errorf("插入用户失败: %v", err)
		}

		id, _ := result.LastInsertId()
		user = User{
			ID:            int(id),
			AndroidID:     androidID,
			Status:        "online",
			FirstSeen:     now,
			LastHeartbeat: now,
		}
	} else if err != nil {
		return nil, false, fmt.Errorf("查询用户失败: %v", err)
	} else {
		// 已有用户，更新为在线（使用 UTC 时间）
		isNew = false
		now := time.Now().UTC()
		_, err = db.Exec(`
			UPDATE users SET status = 'online', last_heartbeat = ?
			WHERE android_id = ?
		`, now, androidID)
		if err != nil {
			return nil, false, fmt.Errorf("更新用户状态失败: %v", err)
		}
		user.Status = "online"
		user.LastHeartbeat = now
	}

	return &user, isNew, nil
}

// updateUserHeartbeat 更新用户心跳时间（使用 UTC 时间）
func updateUserHeartbeat(androidID string) error {
	_, err := db.Exec(`
		UPDATE users SET last_heartbeat = ?, status = 'online'
		WHERE android_id = ?
	`, time.Now().UTC(), androidID)
	return err
}

// updateUserVersion 更新用户版本号
func updateUserVersion(androidID, version string) error {
	_, err := db.Exec(`
		UPDATE users SET app_version = ?
		WHERE android_id = ?
	`, version, androidID)
	return err
}

// updateUserNote 更新用户备注
func updateUserNote(id int, note string) error {
	_, err := db.Exec(`UPDATE users SET note = ? WHERE id = ?`, note, id)
	return err
}

// deleteUser 删除用户
func deleteUser(id int) error {
	_, err := db.Exec(`DELETE FROM users WHERE id = ?`, id)
	return err
}

// getAndroidIDByID 根据ID获取AndroidID
func getAndroidIDByID(id int) (string, error) {
	var androidID string
	err := db.QueryRow(`SELECT android_id FROM users WHERE id = ?`, id).Scan(&androidID)
	return androidID, err
}

// getAllUsers 获取所有用户
func getAllUsers() (*UsersResponse, error) {
	rows, err := db.Query(`
		SELECT id, android_id, status, app_version, first_seen, last_heartbeat, note
		FROM users ORDER BY CASE WHEN status = 'online' THEN 0 ELSE 1 END, id ASC
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var users []User
	onlineCount := 0
	offlineCount := 0

	for rows.Next() {
		var user User
		err := rows.Scan(&user.ID, &user.AndroidID, &user.Status, &user.AppVersion,
			&user.FirstSeen, &user.LastHeartbeat, &user.Note)
		if err != nil {
			continue
		}
		users = append(users, user)
		if user.Status == "online" {
			onlineCount++
		} else {
			offlineCount++
		}
	}

	return &UsersResponse{
		Total:   len(users),
		Online:  onlineCount,
		Offline: offlineCount,
		Users:   users,
	}, nil
}

// setUserOffline 设置用户离线
func setUserOffline(androidID string) {
	_, _ = db.Exec(`UPDATE users SET status = 'offline' WHERE android_id = ?`, androidID)
}

// ==================== 在线人数广播 ====================

func broadcastOnlineCount() {
	clientsMutex.RLock()
	count := len(clients)
	clientsMutex.RUnlock()

	message := fmt.Sprintf("online:%d", count)

	clientsMutex.RLock()
	for _, client := range clients {
		client.mu.Lock()
		if err := client.Conn.WriteMessage(websocket.TextMessage, []byte(message)); err != nil {
			log.Printf("广播在线人数失败 [%s]: %v", client.AndroidID, err)
		}
		client.mu.Unlock()
	}
	clientsMutex.RUnlock()
}

// ==================== WebSocket 处理 ====================

func handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("WebSocket 升级失败: %v", err)
		return
	}

	client := &Client{
		Conn:     conn,
		LastPing: time.Now(),
	}

	defer func() {
		conn.Close()

		if client.AndroidID != "" {
			clientsMutex.Lock()
			delete(clients, client.AndroidID)
			clientsMutex.Unlock()

			setUserOffline(client.AndroidID)
			log.Printf("客户端断开: %s", client.AndroidID)

			// 广播在线人数
			broadcastOnlineCount()
		}
	}()

	log.Println("新的 WebSocket 连接")

	for {
		messageType, message, err := conn.ReadMessage()
		if err != nil {
			log.Printf("读取消息失败: %v", err)
			break
		}

		if messageType == websocket.TextMessage {
			handleMessage(client, message)
		}
	}
}

func handleMessage(client *Client, message []byte) {
	msg := string(message)

	// 尝试解析 JSON 格式（版本号）
	var versionMsg VersionMessage
	if err := json.Unmarshal(message, &versionMsg); err == nil && versionMsg.Type == "version" {
		handleVersionMessage(client, versionMsg.Version)
		return
	}

	// 解析 type:data 格式
	var msgType, data string
	for i, c := range msg {
		if c == ':' {
			msgType = msg[:i]
			data = msg[i+1:]
			break
		}
	}

	if msgType == "" {
		msgType = msg
	}

	switch msgType {
	case "id":
		handleIDMessage(client, data)
	case "ping":
		handlePingMessage(client)
	case "pong":
		handlePongMessage(client)
	case "data":
		handleDataMessage(client, data)
	case "event":
		handleEventMessage(client, data)
	default:
		log.Printf("未知消息类型: %s, 数据: %s", msgType, data)
	}
}

func handleIDMessage(client *Client, androidID string) {
	if androidID == "" {
		return
	}

	client.AndroidID = androidID

	// 注册或获取用户
	_, isNew, err := registerOrGetUser(androidID)
	if err != nil {
		log.Printf("注册用户失败: %v", err)
		return
	}

	// 如果客户端已有版本号，更新到数据库
	if client.AppVersion != "" {
		_ = updateUserVersion(androidID, client.AppVersion)
	}

	// 添加到客户端列表
	clientsMutex.Lock()
	// 如果已有旧连接，先关闭
	if oldClient, exists := clients[androidID]; exists {
		oldClient.Conn.Close()
	}
	clients[androidID] = client
	clientsMutex.Unlock()

	if isNew {
		log.Printf("新用户注册: %s", androidID)
	} else {
		log.Printf("用户重新连接: %s", androidID)
	}

	// 广播在线人数
	broadcastOnlineCount()
}

func handleVersionMessage(client *Client, version string) {
	client.AppVersion = version
	log.Printf("收到版本号 [%s]: %s", client.AndroidID, version)

	if client.AndroidID != "" {
		_ = updateUserVersion(client.AndroidID, version)
	}
}

func handlePingMessage(client *Client) {
	client.mu.Lock()
	client.LastPing = time.Now()
	client.mu.Unlock()

	// 更新心跳时间
	if client.AndroidID != "" {
		_ = updateUserHeartbeat(client.AndroidID)
	}

	// 回复 pong
	client.mu.Lock()
	err := client.Conn.WriteMessage(websocket.TextMessage, []byte("pong:"))
	client.mu.Unlock()

	if err != nil {
		log.Printf("发送 pong 失败: %v", err)
	}
}

func handlePongMessage(client *Client) {
	client.mu.Lock()
	client.LastPing = time.Now()
	client.mu.Unlock()

	if client.AndroidID != "" {
		_ = updateUserHeartbeat(client.AndroidID)
	}
}

func handleDataMessage(client *Client, data string) {
	log.Printf("收到数据消息 [%s]: %s", client.AndroidID, data)
	// 可扩展：广播给其他客户端或处理业务逻辑
}

func handleEventMessage(client *Client, event string) {
	log.Printf("收到事件消息 [%s]: %s", client.AndroidID, event)
	// 可扩展：处理特定事件
}

// ==================== HTTP API 处理 ====================

// 认证中间件
func authMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token := r.Header.Get("X-Token")
		if token == "" {
			token = r.URL.Query().Get("token")
		}

		if token == "" {
			http.Error(w, `{"success":false,"error":"未提供认证令牌"}`, http.StatusUnauthorized)
			return
		}

		// 验证 token（这里简单使用密码比对）
		err := bcrypt.CompareHashAndPassword(adminTokenHash, []byte(token))
		if err != nil {
			http.Error(w, `{"success":false,"error":"认证失败"}`, http.StatusUnauthorized)
			return
		}

		next(w, r)
	}
}

// API: 获取用户列表
func handleGetUsers(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, `{"success":false,"error":"方法不允许"}`, http.StatusMethodNotAllowed)
		return
	}

	// 同步客户端状态：检查超时的连接
	clientsMutex.RLock()
	for androidID, client := range clients {
		client.mu.Lock()
		if time.Since(client.LastPing) > heartbeatTimeout {
			client.Conn.Close()
			delete(clients, androidID)
			setUserOffline(androidID)
		}
		client.mu.Unlock()
	}
	clientsMutex.RUnlock()

	data, err := getAllUsers()
	if err != nil {
		http.Error(w, `{"success":false,"error":"获取用户列表失败"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(data)
}

// API: 更新用户备注
func handleUpdateNote(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut {
		http.Error(w, `{"success":false,"error":"方法不允许"}`, http.StatusMethodNotAllowed)
		return
	}

	idStr := r.URL.Query().Get("id")
	note := r.URL.Query().Get("note")

	if idStr == "" {
		http.Error(w, `{"success":false,"error":"缺少 id 参数"}`, http.StatusBadRequest)
		return
	}

	var id int
	fmt.Sscanf(idStr, "%d", &id)

	err := updateUserNote(id, note)
	if err != nil {
		http.Error(w, `{"success":false,"error":"更新备注失败"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(APIResponse{Success: true})
}

// API: 删除用户
func handleDeleteUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		http.Error(w, `{"success":false,"error":"方法不允许"}`, http.StatusMethodNotAllowed)
		return
	}

	idStr := r.URL.Query().Get("id")
	if idStr == "" {
		http.Error(w, `{"success":false,"error":"缺少 id 参数"}`, http.StatusBadRequest)
		return
	}

	var id int
	fmt.Sscanf(idStr, "%d", &id)

	// 获取 android_id 以便从客户端列表移除
	androidID, err := getAndroidIDByID(id)
	if err == nil && androidID != "" {
		clientsMutex.Lock()
		if client, exists := clients[androidID]; exists {
			client.Conn.Close()
			delete(clients, androidID)
		}
		clientsMutex.Unlock()
	}

	err = deleteUser(id)
	if err != nil {
		http.Error(w, `{"success":false,"error":"删除用户失败"}`, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(APIResponse{Success: true})
}

// ==================== 主函数 ====================

func main() {
	// 初始化数据库
	if err := initDB(); err != nil {
		log.Fatalf("数据库初始化失败: %v", err)
	}
	defer db.Close()

	// 初始化管理员密码
	password := os.Getenv("ADMIN_PASSWORD")
	if password == "" {
		password = defaultAdminPassword
	}
	var err error
	adminTokenHash, err = bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		log.Fatalf("生成密码哈希失败: %v", err)
	}
	log.Printf("管理员密码已设置（默认: %s，可通过环境变量 ADMIN_PASSWORD 修改）", defaultAdminPassword)

	// 设置路由
	http.HandleFunc("/ws", handleWebSocket)
	http.HandleFunc("/api/users", authMiddleware(handleGetUsers))
	http.HandleFunc("/api/users/note", authMiddleware(handleUpdateNote))
	http.HandleFunc("/api/users/delete", authMiddleware(handleDeleteUser))

	// 静态文件服务
	fs := http.FileServer(http.Dir("./static"))
	http.Handle("/", fs)

	// 定时广播在线人数
	go func() {
		ticker := time.NewTicker(onlineBroadcastInterval)
		for range ticker.C {
			broadcastOnlineCount()
		}
	}()

	// 定时检查超时连接
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		for range ticker.C {
			clientsMutex.Lock()
			for androidID, client := range clients {
				client.mu.Lock()
				if time.Since(client.LastPing) > heartbeatTimeout {
					log.Printf("客户端心跳超时，断开: %s", androidID)
					client.Conn.Close()
					delete(clients, androidID)
					setUserOffline(androidID)
				}
				client.mu.Unlock()
			}
			clientsMutex.Unlock()
		}
	}()

	// 启动服务器
	port := os.Getenv("PORT")
	if port == "" {
		port = defaultPort
	}

	log.Printf("服务器启动在端口 %s", port)
	log.Printf("WebSocket 端点: ws://localhost:%s/ws", port)
	log.Printf("管理面板: http://localhost:%s/", port)

	if err := http.ListenAndServe(":"+port, nil); err != nil {
		log.Fatalf("服务器启动失败: %v", err)
	}
}