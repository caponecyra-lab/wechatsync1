# WeChatSync v2 — 服务器搭建 & 媒体同步完整指南

## 目录
1. [服务器搭建（三种方式）](#服务器搭建)
2. [同步逻辑升级说明](#同步逻辑)
3. [媒体文件同步（图片/语音/视频）](#媒体同步)
4. [实时推送配置](#实时推送)
5. [安全加固](#安全)
6. [常见问题](#faq)

---

## 1. 服务器搭建 <a name="服务器搭建"></a>

### 方式 A：Docker 一键部署（推荐）

**要求**：任意 Linux VPS，安装 Docker + Docker Compose

```bash
# 1. 拉取代码
git clone <本项目>
cd WeChatSyncV2/server

# 2. 配置密钥
echo "SECRET_TOKEN=你的密钥$(date +%s | sha256sum | head -c 16)" > .env

# 3. 启动
docker compose up -d

# 4. 查看日志
docker compose logs -f
```

默认端口 3000，确保防火墙放行：
```bash
# Ubuntu / Debian
sudo ufw allow 3000/tcp
# 或者只放行 Nginx（推荐）
sudo ufw allow 80/tcp && sudo ufw allow 443/tcp
```

---

### 方式 B：直接部署到 VPS

**要求**：Node.js ≥ 18，推荐 Ubuntu 22.04

```bash
# 安装 Node.js 20
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs

# 安装 pm2 进程守护
npm install -g pm2

# 部署服务
cd server
npm install
SECRET_TOKEN=你的密钥 pm2 start server.js --name wechat-sync

# 开机自启
pm2 save && pm2 startup

# 查看运行状态
pm2 status
pm2 logs wechat-sync
```

---

### 方式 C：配置 HTTPS（Nginx + Let's Encrypt）

```bash
# 安装 Nginx 和 Certbot
sudo apt install nginx certbot python3-certbot-nginx -y

# 复制 Nginx 配置（修改域名）
sudo cp wechatsync.nginx.conf /etc/nginx/sites-available/wechatsync
sudo nano /etc/nginx/sites-available/wechatsync
# → 将 your-domain.com 替换为你的真实域名

sudo ln -s /etc/nginx/sites-available/wechatsync /etc/nginx/sites-enabled/
sudo nginx -t && sudo nginx -s reload

# 申请免费 SSL 证书（自动续期）
sudo certbot --nginx -d your-domain.com

# 验证 HTTPS
curl https://your-domain.com/api/status
```

---

### 方式 D：家庭内网（无公网 IP）

适用于旧机和新机始终在同一 WiFi 的场景，无需服务器：

```bash
# 在旧手机上安装 Termux
pkg install nodejs
git clone <本项目>
cd server && npm install
node server.js &
```

旧手机的局域网 IP（如 192.168.1.100）即为服务器地址：
`http://192.168.1.100:3000`

---

## 2. 同步逻辑升级 <a name="同步逻辑"></a>

### V2 增量同步流程

```
旧手机                          服务器                    新手机
  │                               │                          │
  │── 读联系人（全量，去重更新）──►│                          │
  │                               │                          │
  │── 读消息（sinceTime 游标）────►│                          │
  │   每个会话单独计算 since        │                          │
  │                               │                          │
  │── 推送（分批 200 条）─────────►│── SSE/WebSocket ────────►│
  │                               │   实时推送新消息           │
  │                               │                          │
  │                               │◄── 拉取（since 游标）─────│
  │                               │    返回增量消息 + 媒体 URL │
```

### 关键改进

| 功能 | V1 | V2 |
|------|----|----|
| 存储 | JSON 文件 | SQLite（WAL 模式，并发安全） |
| 消息去重 | Set 查找 | DB UNIQUE 约束 |
| 增量粒度 | 全局时间戳 | 按会话独立游标 |
| 批量大小 | 无限制 | 每批 200 条 |
| 实时性 | 定期轮询 | SSE 长连接 |
| 媒体 | 不支持 | 图片/语音/视频上传下载 |

---

## 3. 媒体文件同步 <a name="媒体同步"></a>

### 支持类型

| 微信 type | 内容 | 格式 | 大小限制 |
|-----------|------|------|---------|
| 3         | 图片 | JPG/PNG/WebP | 20 MB |
| 34        | 语音 | AMR/SILK | 5 MB |
| 43        | 视频 | MP4 | 100 MB |
| 47        | 表情包 | GIF/PNG | 2 MB |

### 媒体文件在备份中的路径

```
MicroMsg/<hash>/
├── image2/
│   ├── ab/
│   │   └── cdef/
│   │       └── <md5>.jpg        ← 原图
│   └── th_<md5>.jpg             ← 缩略图
├── voice2/
│   └── ab/cdef/<msgid>.amr      ← 语音（AMR 格式）
└── video/
    └── <msgid>.mp4              ← 视频
```

### 上传 API 测试

```bash
# 上传图片
curl -X POST https://your-server.com/api/media/upload \
  -H "X-Device-Token: your-token" \
  -F "image=@/path/to/photo.jpg" \
  -F "msgId=msg123456" \
  -F "mediaKey=unique-key-001"

# 响应：
# {"ok":true,"files":{"image":{"key":"unique-key-001","urlPath":"/uploads/images/abc123.jpg"}}}

# 下载图片（直接访问）
curl https://your-server.com/uploads/images/abc123.jpg -o photo.jpg

# 查询媒体元信息
curl https://your-server.com/api/media/unique-key-001 \
  -H "X-Device-Token: your-token"
```

### 存储空间预估

| 联系人数 | 消息数 | 图片数 | 预估存储 |
|---------|--------|--------|---------|
| 200 人  | 10 万条 | 5000 张 | ~15 GB |
| 500 人  | 50 万条 | 2 万张  | ~60 GB |

建议 VPS 至少 50 GB 存储，或挂载对象存储（OSS / S3）。

---

## 4. 实时推送 <a name="实时推送"></a>

服务器使用 Socket.IO，新手机通过 SSE 长连接或轮询接收实时消息。

在 Android App 中，`RealtimeSyncClient` 自动选择最佳连接方式：
1. SSE 长连接（首选，服务器支持即用）
2. 15 秒轮询（降级，始终可用）

```kotlin
// 在 ViewModel 中使用
val rtClient = RealtimeSyncClient(serverUrl, deviceToken)
rtClient.startListening()

lifecycleScope.launch {
    rtClient.newMessages.collect { events ->
        // 有新消息，更新 UI
        db.messageDao().insertAll(events.map { it.toMessage() })
    }
}
```

---

## 5. 安全加固 <a name="安全"></a>

### 必须做
```bash
# 1. 设置强密钥
SECRET_TOKEN=$(openssl rand -hex 32)

# 2. 开启 HTTPS（见方式 C）

# 3. 限制 IP 访问（只允许你的手机 IP）
# 在 Nginx 中：
# allow 你的IP;
# deny all;
```

### 可选加固
```bash
# 媒体文件加密存储（在 server.js 中加密后存储）
# 定期清理 30 天前的日志
# 开启访问日志

# 查看今日访问量
grep $(date +%Y-%m-%d) /var/log/nginx/access.log | wc -l
```

---

## 6. 常见问题 <a name="faq"></a>

**Q：上传图片很慢怎么办？**
A：在 App 设置中开启「仅 WiFi 上传媒体」，图片会在连接 WiFi 时批量上传。

**Q：服务器磁盘快满了怎么办？**
```bash
# 查看各目录大小
du -sh /path/to/server/uploads/*
# 清理 60 天前的媒体文件（谨慎！）
find /path/to/server/uploads -mtime +60 -delete
```

**Q：语音消息无法播放？**
A：微信语音使用 SILK 格式，部分 Android 系统不支持直接播放，需转码：
```bash
# 服务器端转码（ffmpeg）
ffmpeg -i input.silk -acodec libmp3lame output.mp3
```
在 `server.js` 上传接口中加入自动转码逻辑即可。

**Q：新手机收不到实时消息？**
A：检查服务器是否支持长连接（确保 Nginx `proxy_read_timeout` ≥ 3600），
App 会自动降级到轮询模式（每 15 秒）。

**Q：如何备份服务器数据？**
```bash
# 备份数据库 + 媒体文件
tar -czf wechat_backup_$(date +%Y%m%d).tar.gz data/ uploads/
# 建议每天 cron 自动备份：
echo "0 3 * * * cd /path/to/server && tar -czf ~/backups/wechat_\$(date +\%Y\%m\%d).tar.gz data/ uploads/" | crontab -
```
