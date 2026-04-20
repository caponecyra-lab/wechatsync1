/**
 * WeChatSync 服务器 v2.0
 * ─────────────────────────────────────────────
 * 新增：
 *  · SQLite 持久化（替代 JSON 文件）
 *  · 媒体文件上传 / 下载（图片、语音、视频）
 *  · 增量同步优化（cursor 分页）
 *  · WebSocket 实时推送（新手机即时收消息）
 *  · 设备管理（多设备绑定同一 Token）
 *  · 健康检查 & 监控端点
 */

const express    = require('express');
const http       = require('http');
const { Server } = require('socket.io');
const Database   = require('better-sqlite3');
const multer     = require('multer');
const path       = require('path');
const fs         = require('fs');
const crypto     = require('crypto');

// ── 初始化目录 ──────────────────────────────────
const DATA_DIR   = path.join(__dirname, 'data');
const UPLOAD_DIR = path.join(__dirname, 'uploads');
['images','audio','video','thumb'].forEach(sub =>
  fs.mkdirSync(path.join(UPLOAD_DIR, sub), { recursive: true })
);
fs.mkdirSync(DATA_DIR, { recursive: true });

// ── 数据库初始化 ────────────────────────────────
const db = new Database(path.join(DATA_DIR, 'sync.db'));
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

db.exec(`
  CREATE TABLE IF NOT EXISTS devices (
    token      TEXT PRIMARY KEY,
    role       TEXT NOT NULL DEFAULT 'source',
    label      TEXT,
    last_seen  INTEGER DEFAULT 0
  );

  CREATE TABLE IF NOT EXISTS contacts (
    wx_id        TEXT PRIMARY KEY,
    nickname     TEXT,
    remark       TEXT,
    is_group     INTEGER DEFAULT 0,
    avatar_url   TEXT,
    last_msg_time INTEGER DEFAULT 0,
    last_msg_text TEXT,
    updated_at   INTEGER DEFAULT 0
  );

  CREATE TABLE IF NOT EXISTS messages (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    msg_id       TEXT UNIQUE NOT NULL,
    talker_wx_id TEXT NOT NULL,
    sender_wx_id TEXT,
    content      TEXT,
    type         INTEGER DEFAULT 1,
    is_send      INTEGER DEFAULT 0,
    create_time  INTEGER NOT NULL,
    media_key    TEXT,
    media_url    TEXT,
    media_thumb  TEXT,
    duration_sec INTEGER,
    received_at  INTEGER DEFAULT (strftime('%s','now') * 1000),
    from_device  TEXT
  );

  CREATE TABLE IF NOT EXISTS media_files (
    key         TEXT PRIMARY KEY,
    original    TEXT NOT NULL,
    mime        TEXT,
    size_bytes  INTEGER,
    sha256      TEXT,
    url_path    TEXT NOT NULL,
    thumb_path  TEXT,
    uploaded_at INTEGER DEFAULT (strftime('%s','now') * 1000)
  );

  CREATE INDEX IF NOT EXISTS idx_msg_talker ON messages(talker_wx_id, create_time);
  CREATE INDEX IF NOT EXISTS idx_msg_time   ON messages(create_time);
`);

// ── Prepared statements ─────────────────────────
const stmts = {
  upsertDevice:  db.prepare(`INSERT INTO devices(token,role,label,last_seen)
                              VALUES(?,?,?,?) ON CONFLICT(token) DO UPDATE SET last_seen=excluded.last_seen`),
  upsertContact: db.prepare(`INSERT INTO contacts(wx_id,nickname,remark,is_group,last_msg_time,last_msg_text,updated_at)
                              VALUES(?,?,?,?,?,?,?) ON CONFLICT(wx_id) DO UPDATE SET
                              nickname=excluded.nickname, remark=excluded.remark,
                              last_msg_time=excluded.last_msg_time, last_msg_text=excluded.last_msg_text,
                              updated_at=excluded.updated_at`),
  insertMsg:     db.prepare(`INSERT OR IGNORE INTO messages
                              (msg_id,talker_wx_id,sender_wx_id,content,type,is_send,create_time,media_key,from_device)
                              VALUES(?,?,?,?,?,?,?,?,?)`),
  msgsByTalker:  db.prepare(`SELECT * FROM messages WHERE talker_wx_id=? AND create_time>? ORDER BY create_time ASC LIMIT ?`),
  msgsSince:     db.prepare(`SELECT * FROM messages WHERE create_time>? ORDER BY create_time ASC LIMIT ?`),
  insertMedia:   db.prepare(`INSERT OR REPLACE INTO media_files(key,original,mime,size_bytes,sha256,url_path,thumb_path)
                              VALUES(?,?,?,?,?,?,?)`),
  getMedia:      db.prepare(`SELECT * FROM media_files WHERE key=?`),
  countMsgs:     db.prepare(`SELECT COUNT(*) as c FROM messages`),
  countContacts: db.prepare(`SELECT COUNT(*) as c FROM contacts`),
  latestMsg:     db.prepare(`SELECT MAX(create_time) as t FROM messages`),
};

// ── Multer 媒体上传配置 ─────────────────────────
const storage = multer.diskStorage({
  destination(req, file, cb) {
    const sub = file.fieldname === 'audio' ? 'audio'
              : file.fieldname === 'video' ? 'video' : 'images';
    cb(null, path.join(UPLOAD_DIR, sub));
  },
  filename(req, file, cb) {
    const ext = path.extname(file.originalname) || mimeToExt(file.mimetype);
    cb(null, crypto.randomBytes(16).toString('hex') + ext);
  }
});
const upload = multer({
  storage,
  limits: { fileSize: 100 * 1024 * 1024 }, // 100 MB
  fileFilter(req, file, cb) {
    const allowed = ['image/','audio/','video/'];
    if (allowed.some(p => file.mimetype.startsWith(p))) cb(null, true);
    else cb(new Error('不支持的文件类型: ' + file.mimetype));
  }
});

// ── Express + HTTP + Socket.IO ──────────────────
const app    = express();
const server = http.createServer(app);
const io     = new Server(server, { cors: { origin: '*' } });

app.use(express.json({ limit: '10mb' }));
app.use('/uploads', express.static(UPLOAD_DIR)); // 媒体文件静态访问

// ── 认证中间件 ──────────────────────────────────
const SECRET = process.env.SECRET_TOKEN || '';
function auth(req, res, next) {
  if (!SECRET) return next();
  const token = req.headers['x-device-token'] || req.query.token;
  if (!token) return res.status(401).json({ error: '缺少 X-Device-Token' });
  next();
}

// 记录设备活跃时间
function touchDevice(token, role = 'source') {
  if (!token) return;
  stmts.upsertDevice.run(token, role, null, Date.now());
}

// ── WebSocket 连接管理 ──────────────────────────
const rooms = new Map(); // token → Set<socketId>
io.on('connection', socket => {
  const token = socket.handshake.query.token;
  if (!token) { socket.disconnect(); return; }
  if (!rooms.has(token)) rooms.set(token, new Set());
  rooms.get(token).add(socket.id);
  console.log(`[WS] 连接: token=${token} socket=${socket.id}`);

  socket.on('disconnect', () => {
    rooms.get(token)?.delete(socket.id);
  });
});

function pushToTargets(token, event, data) {
  const sockets = rooms.get(token);
  if (sockets?.size) io.to([...sockets]).emit(event, data);
}

// ─────────────────────────────────────────────────
// 路由
// ─────────────────────────────────────────────────

/** 服务器状态 */
app.get('/api/status', auth, (req, res) => {
  const msgs = stmts.countMsgs.get();
  const contacts = stmts.countContacts.get();
  const latest = stmts.latestMsg.get();
  res.json({
    ok: true,
    version: '2.0.0',
    totalMessages: msgs.c,
    totalContacts: contacts.c,
    lastMessageTime: latest.t || 0,
    uptime: Math.floor(process.uptime()),
    mediaDir: UPLOAD_DIR
  });
});

/** ── PUSH：旧手机推送聊天记录 ─────────────── */
app.post('/api/sync/push', auth, (req, res) => {
  const { device, contacts = [], messages = [] } = req.body;
  if (!device) return res.status(400).json({ error: '缺少 device' });
  touchDevice(device, 'source');

  const now = Date.now();
  const insertMany = db.transaction(() => {
    let accepted = 0;
    contacts.forEach(c => {
      stmts.upsertContact.run(
        c.wxId, c.nickname || '', c.remark || '',
        c.isGroup ? 1 : 0,
        c.lastMsgTime || 0, c.lastMsgContent || '', now
      );
    });
    messages.forEach(m => {
      const r = stmts.insertMsg.run(
        m.msgId, m.talkerWxId, m.senderWxId || '',
        m.content || '', m.type || 1, m.isSend ? 1 : 0,
        m.createTime, m.mediaKey || null, device
      );
      if (r.changes > 0) accepted++;
    });
    return accepted;
  });

  const accepted = insertMany();

  // 实时推送给同 token 的新手机
  if (accepted > 0) {
    pushToTargets(device, 'new_messages', { count: accepted, messages });
  }

  console.log(`[PUSH] device=${device} msgs=${messages.length} new=${accepted}`);
  res.json({ ok: true, accepted, total: stmts.countMsgs.get().c });
});

/** ── PULL：新手机增量拉取 ─────────────────── */
app.get('/api/sync/pull', auth, (req, res) => {
  const since  = parseInt(req.query.since)  || 0;
  const limit  = Math.min(parseInt(req.query.limit) || 500, 2000);
  const wxId   = req.query.wxId || '';
  const device = req.query.device || '';
  touchDevice(device, 'target');

  const msgs = wxId
    ? stmts.msgsByTalker.all(wxId, since, limit)
    : stmts.msgsSince.all(since, limit);

  // 补全媒体 URL
  msgs.forEach(m => {
    if (m.media_key) {
      const mf = stmts.getMedia.get(m.media_key);
      if (mf) {
        m.mediaUrl  = `${req.protocol}://${req.get('host')}${mf.url_path}`;
        m.thumbUrl  = mf.thumb_path
          ? `${req.protocol}://${req.get('host')}${mf.thumb_path}` : null;
      }
    }
  });

  res.json({ ok: true, messages: msgs, count: msgs.length, hasMore: msgs.length === limit });
});

/** ── 联系人列表 ─────────────────────────── */
app.get('/api/sync/contacts', auth, (req, res) => {
  const rows = db.prepare('SELECT * FROM contacts ORDER BY last_msg_time DESC').all();
  res.json({ ok: true, contacts: rows });
});

// ─────────────────────────────────────────────────
// 媒体文件接口
// ─────────────────────────────────────────────────

/**
 * POST /api/media/upload
 * 字段：image | audio | video（三选一）
 * 附带：msgId, mediaKey（可选）
 */
app.post('/api/media/upload',
  auth,
  upload.fields([
    { name: 'image', maxCount: 1 },
    { name: 'audio', maxCount: 1 },
    { name: 'video', maxCount: 1 },
    { name: 'thumb', maxCount: 1 },
  ]),
  (req, res) => {
    try {
      const files = req.files;
      const results = {};

      for (const [field, arr] of Object.entries(files || {})) {
        if (field === 'thumb') continue;
        const file = arr[0];
        const sha  = fileSha256(file.path);
        const key  = req.body.mediaKey || sha;
        const urlPath = `/uploads/${field === 'audio' ? 'audio' : field === 'video' ? 'video' : 'images'}/${file.filename}`;

        // 缩略图（图片和视频）
        let thumbPath = null;
        if (files.thumb?.[0]) {
          const tf = files.thumb[0];
          thumbPath = `/uploads/thumb/${tf.filename}`;
          stmts.insertMedia.run(key + '_thumb', tf.originalname, tf.mimetype,
            tf.size, fileSha256(tf.path), thumbPath, null);
        }

        stmts.insertMedia.run(key, file.originalname, file.mimetype,
          file.size, sha, urlPath, thumbPath);

        // 更新对应消息的 media_url
        if (req.body.msgId) {
          db.prepare(`UPDATE messages SET media_key=?, media_url=? WHERE msg_id=?`)
            .run(key, urlPath, req.body.msgId);
        }

        results[field] = { key, urlPath, thumbPath, size: file.size };
      }

      res.json({ ok: true, files: results });
    } catch (e) {
      console.error('[UPLOAD ERR]', e);
      res.status(500).json({ error: e.message });
    }
  }
);

/** GET /api/media/:key — 获取媒体文件元信息 */
app.get('/api/media/:key', auth, (req, res) => {
  const mf = stmts.getMedia.get(req.params.key);
  if (!mf) return res.status(404).json({ error: 'Not found' });
  const base = `${req.protocol}://${req.get('host')}`;
  res.json({
    ok: true,
    key: mf.key,
    url: base + mf.url_path,
    thumb: mf.thumb_path ? base + mf.thumb_path : null,
    mime: mf.mime,
    size: mf.size_bytes
  });
});

// ─────────────────────────────────────────────────
// 工具函数
// ─────────────────────────────────────────────────

function fileSha256(filePath) {
  const buf = fs.readFileSync(filePath);
  return crypto.createHash('sha256').update(buf).digest('hex').slice(0, 16);
}

function mimeToExt(mime) {
  const map = {
    'image/jpeg': '.jpg', 'image/png': '.png', 'image/gif': '.gif',
    'image/webp': '.webp', 'audio/amr': '.amr', 'audio/mp4': '.m4a',
    'audio/mpeg': '.mp3', 'video/mp4': '.mp4'
  };
  return map[mime] || '';
}

// ─────────────────────────────────────────────────
// 启动
// ─────────────────────────────────────────────────

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`\n✅  WeChatSync 服务器 v2.0 已启动`);
  console.log(`   地址  : http://0.0.0.0:${PORT}`);
  console.log(`   数据库: ${path.join(DATA_DIR, 'sync.db')}`);
  console.log(`   媒体  : ${UPLOAD_DIR}`);
  if (!SECRET) console.warn('   ⚠️  未设置 SECRET_TOKEN，建议生产环境配置！\n');
});
