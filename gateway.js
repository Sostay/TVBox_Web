/**
 * TVBox Web Gateway & Stream Proxy (Zero-dependency Node.js)
 * Port: 8999
 */
const http = require('http');
const https = require('https');
const url = require('url');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { inspectMediaTracks, swapMp4AudioTrack, swapMkvAudioTrack } = require('./lib/media-inspector');

const PORT = 8999;
const BACKEND_PORT = 9988;
const BACKEND_URL = `http://127.0.0.1:${BACKEND_PORT}`;

// 1. Ensure backend (index.js) is running
let backendProcess = null;

function checkBackendReady(retries = 10, delay = 1000) {
  return new Promise((resolve, reject) => {
    const check = () => {
      const req = http.get(`${BACKEND_URL}/health`, (res) => {
        if (res.statusCode === 200) {
          resolve(true);
        } else {
          retry();
        }
      });
      req.on('error', () => retry());
      req.setTimeout(1000, () => {
        req.destroy();
        retry();
      });
    };

    const retry = () => {
      if (retries <= 0) {
        return reject(new Error('Backend failed to start'));
      }
      retries--;
      setTimeout(check, delay);
    };

    check();
  });
}

function startBackend() {
  console.log('[Gateway] Starting backend index.js...');
  backendProcess = spawn(process.execPath, [path.join(__dirname, 'index.js')], {
    cwd: __dirname,
    stdio: 'inherit',
    env: {
      ...process.env,
      PORT: String(BACKEND_PORT),
      HOST: '127.0.0.1'
    }
  });

  backendProcess.on('exit', (code, signal) => {
    console.log(`[Gateway] Backend exited with code ${code} signal ${signal}`);
  });
}

// 2. Stream Proxy with Range & M3U8 Rewriting
function handleStreamProxy(req, res, targetUrl, customHeadersStr) {
  let headers = {};
  if (customHeadersStr) {
    try {
      const parsed = JSON.parse(customHeadersStr);
      // Normalize all custom header keys to lowercase to avoid case-insensitive collisions in Node
      for (const [k, v] of Object.entries(parsed)) {
        headers[k.toLowerCase()] = v;
      }
    } catch (e) {
      console.error('[StreamProxy] Failed to parse custom headers:', e);
    }
  }

  // Pass-through Range header from client for video seeking
  if (req.headers.range) {
    headers['range'] = req.headers.range;
  }
  // Only use browser user-agent if the spider did NOT specify one
  if (!headers['user-agent']) {
    headers['user-agent'] = req.headers['user-agent'] || 'Mozilla/5.0';
  }

  const parsedUrl = new URL(targetUrl);
  const clientLib = parsedUrl.protocol === 'https:' ? https : http;

  const proxyReq = clientLib.request(targetUrl, {
    method: 'GET',
    headers: {
      ...headers,
      host: parsedUrl.host
    }
  }, (proxyRes) => {
    // Handle redirects (301, 302, 303, 307, 308)
    if ([301, 302, 303, 307, 308].includes(proxyRes.statusCode) && proxyRes.headers.location) {
      const redirectUrl = new URL(proxyRes.headers.location, targetUrl).href;
      return handleStreamProxy(req, res, redirectUrl, customHeadersStr);
    }

    const contentType = proxyRes.headers['content-type'] || '';
    const isM3u8 = contentType.includes('mpegurl') || parsedUrl.pathname.endsWith('.m3u8');

    // If it's an M3U8 playlist, rewrite the lines
    if (isM3u8) {
      let chunks = [];
      proxyRes.on('data', chunk => chunks.push(chunk));
      proxyRes.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf-8');
        const baseUrl = targetUrl.substring(0, targetUrl.lastIndexOf('/') + 1);

        const rewritten = body.split('\n').map(line => {
          const trimmed = line.trim();
          if (!trimmed || trimmed.startsWith('#')) {
            // Check for URI in tags like #EXT-X-KEY, #EXT-X-MAP, #EXT-X-MEDIA (Audio/Subtitles)
            if (trimmed.startsWith('#') && (trimmed.includes('URI="') || trimmed.includes("URI='"))) {
              return trimmed.replace(/URI=["']([^"']+)["']/g, (m, uri) => {
                const absUri = new URL(uri, baseUrl).href;
                const proxied = `/api/stream?url=${encodeURIComponent(absUri)}&headers=${encodeURIComponent(customHeadersStr || '{}')}`;
                return `URI="${proxied}"`;
              });
            }
            return line;
          }

          // Relative or absolute media segment URL
          const absSegmentUrl = new URL(trimmed, baseUrl).href;
          return `/api/stream?url=${encodeURIComponent(absSegmentUrl)}&headers=${encodeURIComponent(customHeadersStr || '{}')}`;
        }).join('\n');

        res.writeHead(proxyRes.statusCode, {
          'Content-Type': 'application/vnd.apple.mpegurl',
          'Access-Control-Allow-Origin': '*',
          'Cache-Control': 'no-cache'
        });
        res.end(rewritten);
      });
      return;
    }

    // Direct streaming for TS / MP4 chunks
    let contentTypeHeader = proxyRes.headers['content-type'] || '';
    if (!contentTypeHeader || contentTypeHeader === 'application/octet-stream' || contentTypeHeader === 'binary/octet-stream') {
      if (parsedUrl.pathname.endsWith('.mp4') || targetUrl.includes('.mp4')) {
        contentTypeHeader = 'video/mp4';
      } else if (parsedUrl.pathname.endsWith('.mkv') || targetUrl.includes('.mkv')) {
        contentTypeHeader = 'video/x-matroska';
      }
    }

    const resHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': '*',
      'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Accept-Ranges'
    };
    if (contentTypeHeader) resHeaders['Content-Type'] = contentTypeHeader;
    if (proxyRes.headers['content-length']) resHeaders['Content-Length'] = proxyRes.headers['content-length'];
    if (proxyRes.headers['content-range']) resHeaders['Content-Range'] = proxyRes.headers['content-range'];
    if (proxyRes.headers['accept-ranges']) resHeaders['Accept-Ranges'] = proxyRes.headers['accept-ranges'];

    res.writeHead(proxyRes.statusCode, resHeaders);

    const reqUrlObj = new URL(req.url, 'http://127.0.0.1:8999');
    const hasAudioTrackParam = reqUrlObj.searchParams.has('audio_track');
    const targetAudioTrack = hasAudioTrackParam
      ? parseInt(reqUrlObj.searchParams.get('audio_track'), 10)
      : -1;
    const rangeHeader = req.headers.range || '';
    const isStartOfFile = !rangeHeader || rangeHeader.startsWith('bytes=0-');

    // Small range probe check (e.g. bytes=0-999 or < 128KB header sniffing)
    let isSmallProbe = false;
    if (rangeHeader && rangeHeader.startsWith('bytes=0-')) {
      const match = rangeHeader.match(/bytes=0-(\d+)/);
      if (match && parseInt(match[1], 10) < 131072) {
        isSmallProbe = true;
      }
    }

    if (!isSmallProbe && hasAudioTrackParam && targetAudioTrack >= 0 && isStartOfFile && (contentTypeHeader === 'video/mp4' || contentTypeHeader === 'video/x-matroska' || targetUrl.includes('.mp4') || targetUrl.includes('.mkv') || !isM3u8)) {
      let initialChunks = [];
      let totalBytes = 0;
      let intercepted = false;
      let targetScanBytes = 8 * 1024 * 1024; // default 8MB
      let detectedHeader = false;

      proxyRes.on('data', (chunk) => {
        if (!intercepted) {
          initialChunks.push(chunk);
          totalBytes += chunk.length;

          // Detect header size early to avoid buffering more than needed
          if (!detectedHeader && totalBytes >= 64) {
            const probe = Buffer.concat(initialChunks);
            // Check if MKV (EBML 0x1A45DFA3)
            if (probe[0] === 0x1a && probe[1] === 0x45 && probe[2] === 0xdf && probe[3] === 0xa3) {
              detectedHeader = true;
              targetScanBytes = 256 * 1024; // 256KB is plenty for any MKV Tracks header
            } else {
              // Check MP4 moov
              const prefix = (probe.toString('hex', 0, 4) === '89504e47') ? 8 : 0;
              let p = prefix;
              while (p + 8 <= probe.length) {
                const sz = probe.readUInt32BE(p);
                const ty = probe.toString('latin1', p + 4, p + 8);
                if (ty === 'moov') {
                  detectedHeader = true;
                  targetScanBytes = Math.min(p + sz + 64, 16 * 1024 * 1024);
                  break;
                }
                if (sz <= 0 || p + sz > probe.length) break;
                p += sz;
              }
            }
          }

          if (totalBytes >= targetScanBytes) {
            intercepted = true;
            const fullBuf = Buffer.concat(initialChunks);
            let modified;
            if (fullBuf[0] === 0x1a && fullBuf[1] === 0x45 && fullBuf[2] === 0xdf && fullBuf[3] === 0xa3) {
              modified = swapMkvAudioTrack(fullBuf, targetAudioTrack);
            } else {
              modified = swapMp4AudioTrack(fullBuf, targetAudioTrack);
            }
            initialChunks = null;
            if (!res.destroyed && !res.writableEnded) {
              const ok = res.write(modified);
              if (!ok) {
                proxyRes.pause();
                res.once('drain', () => {
                  if (!proxyRes.destroyed) proxyRes.resume();
                });
              }
            }
          }
        } else {
          if (!res.destroyed && !res.writableEnded) {
            const ok = res.write(chunk);
            if (!ok) {
              proxyRes.pause();
              res.once('drain', () => {
                if (!proxyRes.destroyed) proxyRes.resume();
              });
            }
          }
        }
      });

      proxyRes.on('end', () => {
        if (!intercepted && initialChunks && initialChunks.length > 0) {
          const fullBuf = Buffer.concat(initialChunks);
          let modified;
          if (fullBuf[0] === 0x1a && fullBuf[1] === 0x45 && fullBuf[2] === 0xdf && fullBuf[3] === 0xa3) {
            modified = swapMkvAudioTrack(fullBuf, targetAudioTrack);
          } else {
            modified = swapMp4AudioTrack(fullBuf, targetAudioTrack);
          }
          if (!res.destroyed && !res.writableEnded) {
            res.write(modified);
          }
          initialChunks = null;
        }
        if (!res.destroyed && !res.writableEnded) {
          res.end();
        }
      });

      proxyRes.on('error', (err) => {
        if (!res.destroyed) {
          res.destroy(err);
        }
      });

      return;
    }

    proxyRes.pipe(res);
  });

  proxyReq.on('error', (err) => {
    console.error('[StreamProxy] Proxy request error:', err.message);
    if (!res.headersSent) {
      res.writeHead(502, { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' });
      res.end(`Proxy Error: ${err.message}`);
    }
  });

  // Client lifecycle & connection leak prevention
  const cleanup = () => {
    if (!proxyReq.destroyed) {
      proxyReq.destroy();
    }
  };

  res.on('close', () => {
    if (!res.writableEnded) {
      cleanup();
    }
  });
  req.on('error', cleanup);
  res.on('error', cleanup);

  proxyReq.end();
}

// 3. Fetch external JSON / Source config URL proxy
function handleFetchUrl(req, res, targetUrl) {
  try {
    const parsedUrl = new URL(targetUrl);
    const clientLib = parsedUrl.protocol === 'https:' ? https : http;

    const fetchReq = clientLib.get(targetUrl, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
      }
    }, (fetchRes) => {
      // Handle redirects
      if ([301, 302, 303, 307, 308].includes(fetchRes.statusCode) && fetchRes.headers.location) {
        const redirectUrl = new URL(fetchRes.headers.location, targetUrl).href;
        return handleFetchUrl(req, res, redirectUrl);
      }

      res.writeHead(fetchRes.statusCode, {
        'Content-Type': 'application/json; charset=utf-8',
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, OPTIONS'
      });
      fetchRes.pipe(res);
    });

    fetchReq.on('error', (err) => {
      res.writeHead(500, {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*'
      });
      res.end(JSON.stringify({ error: 'Failed to fetch external URL', message: err.message }));
    });
  } catch (err) {
    res.writeHead(400, {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*'
    });
    res.end(JSON.stringify({ error: 'Invalid URL', message: err.message }));
  }
}

// 4. Reverse Proxy for /api/* and /website/* to Backend
function forwardToBackend(req, res, targetPath) {
  const reqUrl = new URL(req.url, `http://${req.headers.host}`);
  const destPath = targetPath || reqUrl.pathname;
  const backendTarget = `${BACKEND_URL}${destPath}${reqUrl.search}`;

  const proxyReq = http.request(backendTarget, {
    method: req.method,
    headers: {
      ...req.headers,
      host: `127.0.0.1:${BACKEND_PORT}`
    }
  }, (proxyRes) => {
    const resHeaders = {
      ...proxyRes.headers,
      'access-control-allow-origin': '*',
      'access-control-allow-methods': 'GET, POST, OPTIONS, PUT, DELETE',
      'access-control-allow-headers': '*'
    };
    res.writeHead(proxyRes.statusCode, resHeaders);
    proxyRes.pipe(res);
  });

  proxyReq.on('error', (err) => {
    console.error('[Gateway] Backend forward error:', err.message);
    if (!res.headersSent) {
      res.writeHead(502, { 'Content-Type': 'application/json', 'access-control-allow-origin': '*' });
      res.end(JSON.stringify({ error: 'Backend unreachable', message: err.message }));
    }
  });

  res.on('close', () => {
    if (!res.writableEnded && !proxyReq.destroyed) {
      proxyReq.destroy();
    }
  });

  if (req.method === 'GET' || req.method === 'HEAD') {
    proxyReq.end();
  } else {
    req.pipe(proxyReq);
  }
}

// 5. HTTP Gateway Server
const server = http.createServer((req, res) => {
  // CORS Preflight
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS, PUT, DELETE',
      'Access-Control-Allow-Headers': '*'
    });
    return res.end();
  }

  const parsedUrl = new URL(req.url, `http://${req.headers.host}`);
  const pathname = parsedUrl.pathname;

  // Stream proxy endpoint
  if (pathname === '/api/stream') {
    const target = parsedUrl.searchParams.get('url');
    const headersStr = parsedUrl.searchParams.get('headers');
    if (!target) {
      res.writeHead(400, { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' });
      return res.end('Missing url parameter');
    }
    return handleStreamProxy(req, res, target, headersStr);
  }

  // Stream media tracks inspection (Audio & Subtitle tracks in MP4 / MKV / HLS)
  if (pathname === '/api/media_tracks') {
    const target = parsedUrl.searchParams.get('url');
    const headersStr = parsedUrl.searchParams.get('headers');
    if (!target) {
      res.writeHead(400, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
      return res.end(JSON.stringify({ error: 'Missing url parameter' }));
    }

    let headers = {};
    if (headersStr) {
      try { headers = JSON.parse(headersStr); } catch (e) {}
    }

    inspectMediaTracks(target, headers).then((meta) => {
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Access-Control-Allow-Origin': '*'
      });
      res.end(JSON.stringify(meta));
    }).catch((err) => {
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Access-Control-Allow-Origin': '*'
      });
      res.end(JSON.stringify({ success: false, audioTracks: [], subtitleTracks: [], error: err.message }));
    });
    return;
  }

  // Fetch external JSON config (e.g. user subscribed json)
  if (pathname === '/api/fetch_source') {
    const target = parsedUrl.searchParams.get('url');
    if (!target) {
      res.writeHead(400, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
      return res.end(JSON.stringify({ error: 'Missing url parameter' }));
    }
    return handleFetchUrl(req, res, target);
  }

  // API routing to backend
  if (pathname.startsWith('/api/')) {
    const backendPath = pathname.replace(/^\/api/, '');
    return forwardToBackend(req, res, backendPath);
  }

  // Website configuration center routing
  if (pathname === '/website' || pathname === '/website/' || pathname === '/website/index.html') {
    // Serve enhanced website.html from public/ directory
    const websitePath = path.join(__dirname, 'public', 'website.html');
    if (fs.existsSync(websitePath)) {
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Access-Control-Allow-Origin': '*'
      });
      return fs.createReadStream(websitePath).pipe(res);
    }
  }
  if (pathname.startsWith('/website') || pathname.startsWith('/siteCookie') || pathname.startsWith('/danmu')) {
    return forwardToBackend(req, res, pathname);
  }

  // Static web frontend & PWA assets
  const staticFileMap = {
    '/': 'index.html',
    '/index.html': 'index.html',
    '/manifest.webmanifest': 'manifest.webmanifest',
    '/manifest.json': 'manifest.webmanifest',
    '/sw.js': 'sw.js',
    '/icon-192.png': 'icon-192.png',
    '/icon-512.png': 'icon-512.png',
    '/favicon.ico': 'favicon.ico',
    '/tailwind.min.js': 'tailwind.min.js',
    '/hls.min.js': 'hls.min.js'
  };

  if (staticFileMap[pathname]) {
    const filePath = path.join(__dirname, 'public', staticFileMap[pathname]);
    const ext = path.extname(filePath).toLowerCase();
    const mimeTypes = {
      '.html': 'text/html; charset=utf-8',
      '.webmanifest': 'application/manifest+json; charset=utf-8',
      '.json': 'application/json; charset=utf-8',
      '.js': 'application/javascript; charset=utf-8',
      '.png': 'image/png',
      '.ico': 'image/x-icon'
    };
    fs.readFile(filePath, (err, content) => {
      if (err) {
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        return res.end('File not found: ' + pathname);
      }
      res.writeHead(200, {
        'Content-Type': mimeTypes[ext] || 'application/octet-stream',
        'Access-Control-Allow-Origin': '*'
      });
      res.end(content);
    });
    return;
  }

  // Fallback 404
  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not Found');
});

// Bootstrap
async function main() {
  startBackend();
  try {
    await checkBackendReady();
    console.log('[Gateway] Backend index.js is healthy and ready!');
  } catch (err) {
    console.error('[Gateway] Warning: Backend readiness check timed out:', err.message);
  }

  server.on('error', (err) => {
    if (err.code === 'EACCES') {
      console.error(`\n======================================================`);
      console.error(`❌ [端口冲突错误] 无法绑定端口 ${PORT} (EACCES 权限被拒绝)！`);
      console.error(`原因：Windows Hyper-V / WSL / WinNAT 动态端口池占用了 ${PORT} 端口。`);
      console.error(`解决办法：`);
      console.error(`  1. 双击运行项目目录下的【修复端口占用.bat】自动释放并修复；`);
      console.error(`  2. 或以管理员身份打开 CMD 执行：net stop winnat && net start winnat`);
      console.error(`======================================================\n`);
    } else if (err.code === 'EADDRINUSE') {
      console.error(`\n======================================================`);
      console.error(`❌ [端口冲突错误] 端口 ${PORT} 已被其他程序占用 (EADDRINUSE)！`);
      console.error(`解决办法：请先双击运行 stop.bat 关闭残留进程后重试。`);
      console.error(`======================================================\n`);
    } else {
      console.error('[Gateway] Server error:', err.message);
    }
    process.exit(1);
  });

  server.listen(PORT, '0.0.0.0', () => {
    console.log(`\n======================================================`);
    console.log(`🚀 TVBox Web Gateway running at http://127.0.0.1:${PORT}`);
    console.log(`📱 LAN Devices can access: http://<Your-IP>:${PORT}`);
    console.log(`⚙️  Cloud Drive Config Center: http://127.0.0.1:${PORT}/website`);
    console.log(`======================================================\n`);
  });
}

main();
