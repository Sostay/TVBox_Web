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
      headers = JSON.parse(customHeadersStr);
    } catch (e) {
      console.error('[StreamProxy] Failed to parse custom headers:', e);
    }
  }

  // Pass-through Range header from client for video seeking
  if (req.headers.range) {
    headers['range'] = req.headers.range;
  }
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
            // Check for URI in tags like #EXT-X-KEY:METHOD=AES-128,URI="key.key"
            if (trimmed.startsWith('#EXT-X-KEY') || trimmed.startsWith('#EXT-X-MAP')) {
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
    const resHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': '*',
      'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Accept-Ranges'
    };
    if (proxyRes.headers['content-type']) resHeaders['Content-Type'] = proxyRes.headers['content-type'];
    if (proxyRes.headers['content-length']) resHeaders['Content-Length'] = proxyRes.headers['content-length'];
    if (proxyRes.headers['content-range']) resHeaders['Content-Range'] = proxyRes.headers['content-range'];
    if (proxyRes.headers['accept-ranges']) resHeaders['Accept-Ranges'] = proxyRes.headers['accept-ranges'];

    res.writeHead(proxyRes.statusCode, resHeaders);
    proxyRes.pipe(res);
  });

  proxyReq.on('error', (err) => {
    console.error('[StreamProxy] Proxy request error:', err.message);
    if (!res.headersSent) {
      res.writeHead(502, { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' });
      res.end(`Proxy Error: ${err.message}`);
    }
  });

  req.on('aborted', () => {
    proxyReq.destroy();
  });

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

  req.pipe(proxyReq);
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
  if (pathname.startsWith('/website') || pathname.startsWith('/siteCookie') || pathname.startsWith('/danmu')) {
    return forwardToBackend(req, res, pathname);
  }

  // Static web frontend
  if (pathname === '/' || pathname === '/index.html') {
    const indexPath = path.join(__dirname, 'public', 'index.html');
    fs.readFile(indexPath, 'utf-8', (err, content) => {
      if (err) {
        res.writeHead(500, { 'Content-Type': 'text/plain' });
        return res.end('Error loading frontend index.html: ' + err.message);
      }
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
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

  server.listen(PORT, '0.0.0.0', () => {
    console.log(`\n======================================================`);
    console.log(`🚀 TVBox Web Gateway running at http://127.0.0.1:${PORT}`);
    console.log(`📱 LAN Devices can access: http://<Your-IP>:${PORT}`);
    console.log(`⚙️  Cloud Drive Config Center: http://127.0.0.1:${PORT}/website`);
    console.log(`======================================================\n`);
  });
}

main();
