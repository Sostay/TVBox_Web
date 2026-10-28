/**
 * TVBox Web – media-inspector.js
 * Zero-dependency Node.js module for MP4/MKV/HLS track inspection and MP4 audio-track swapping.
 * Used by gateway.js via require('./lib/media-inspector').
 */
'use strict';

const http = require('http');
const https = require('https');
const { URL } = require('url');

// ─── helpers ──────────────────────────────────────────────────────────────────

/**
 * Fetch a byte range from a remote URL with caller-supplied headers.
 * Returns a Buffer.  Follows up to one redirect level.
 */
function fetchByteRange(targetUrl, headers, start, end) {
  return new Promise((resolve, reject) => {
    try {
      const parsed   = new URL(targetUrl);
      const lib      = parsed.protocol === 'https:' ? https : http;
      const outH     = {};
      for (const [k, v] of Object.entries(headers || {})) {
        outH[k.toLowerCase()] = v;
      }
      outH['range']    = `bytes=${start}-${end}`;
      outH['host']     = parsed.host;
      if (!outH['user-agent']) outH['user-agent'] = 'Mozilla/5.0';

      const req = lib.get(targetUrl, { headers: outH, timeout: 12000 }, (res) => {
        if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location) {
          return resolve(fetchByteRange(new URL(res.headers.location, targetUrl).href, headers, start, end));
        }
        if (res.statusCode !== 200 && res.statusCode !== 206) {
          res.resume();
          return reject(new Error(`HTTP ${res.statusCode}`));
        }
        const chunks = [];
        res.on('data', c => chunks.push(c));
        res.on('end', () => resolve(Buffer.concat(chunks)));
        res.on('error', reject);
      });
      req.on('timeout', () => { req.destroy(); reject(new Error('fetch timeout')); });
      req.on('error', reject);
    } catch (e) { reject(e); }
  });
}

/** Map ISO-639-2/T + common codes to a Chinese display label */
function getLangLabel(code) {
  const c = String(code || '').toLowerCase().replace(/\0/g, '');
  const map = {
    chi: '中文', zho: '中文', zh: '中文', chs: '中文', cht: '中文(繁)',
    yue: '粤语', canto: '粤语',
    eng: '英语', en: '英语',
    jpn: '日语', ja: '日语',
    kor: '한국어', ko: '한국어',
    fre: '法语', fra: '法语', fr: '法语',
    ger: '德语', deu: '德语', de: '德语',
    rus: '俄语', ru: '俄语',
    spa: '西语', es: '西语',
    por: '葡语', pt: '葡语',
    tha: '泰语', ara: '阿拉伯语',
  };
  // Quark transcoder leaves language=0x0000 → 'und' or garbage 3-char codes
  // that don't appear in the map → fall through to '中文' default
  return map[c] || '中文';
}

// ─── MP4 box walker ───────────────────────────────────────────────────────────

/** Walk flat list of boxes in `buf[start..end)`, calling cb(type, boxStart, dataStart, dataEnd) */
function walkBoxes(buf, start, end, cb) {
  let p = start;
  while (p + 8 <= end && p >= 0) {
    let size = buf.readUInt32BE(p);
    if (size === 0) { size = end - p; }    // box extends to end of container
    if (size < 8)   break;

    const type      = buf.toString('latin1', p + 4, p + 8);
    let   dataStart = p + 8;
    if (size === 1) {                       // 64-bit extended size
      if (p + 16 > end) break;
      size      = Number(buf.readBigUInt64BE(p + 8));
      dataStart = p + 16;
      if (size < 16) break;
    }
    const nextP = Math.min(buf.length, p + size);
    if (nextP <= p) break; // strictly prevent 0 or negative advancement
    cb(type, p, dataStart, nextP);
    p = nextP;
  }
}

/**
 * Parse the MP4 moov box (already fully in `buf`) and return track info.
 * `buf` must contain the complete moov atom.
 */
function parseMoovBuffer(buf) {
  // find moov at top level
  let moovStart = -1, moovDataStart = -1, moovEnd = -1;
  walkBoxes(buf, 0, buf.length, (type, bs, ds, be) => {
    if (type === 'moov') { moovStart = bs; moovDataStart = ds; moovEnd = be; }
  });
  if (moovStart === -1) return null;

  const audioTracks    = [];
  const subtitleTracks = [];

  // walk trak boxes inside moov
  walkBoxes(buf, moovDataStart, moovEnd, (type, trakStart, trakDataStart, trakEnd) => {
    if (type !== 'trak') return;

    // ── tkhd ──
    let trackId = 0, trakFlags = 0, tkhdEnabledByteOffset = -1;
    walkBoxes(buf, trakDataStart, trakEnd, (t, bs, ds) => {
      if (t !== 'tkhd') return;
      const ver = buf[ds];
      trakFlags = (buf[ds + 1] << 16) | (buf[ds + 2] << 8) | buf[ds + 3];
      // tkhd layout: [size:4][type:4][version:1][flags:3]...  flags at ds+1..ds+3
      // enabled bit is bit 0 of the flags → LSB of buf[ds+3]
      tkhdEnabledByteOffset = ds + 3;
      // v0: ver(1)+flags(3)+ctime(4)+mtime(4) = 12 bytes → trackId at ds+12
      // v1: ver(1)+flags(3)+ctime(8)+mtime(8) = 20 bytes → trackId at ds+20
      trackId = ver === 1 ? buf.readUInt32BE(ds + 20) : buf.readUInt32BE(ds + 12);
    });

    // ── mdia/mdhd (language) ──
    let lang = 'und';
    walkBoxes(buf, trakDataStart, trakEnd, (t, bs, ds) => {
      if (t !== 'mdia') return;
      walkBoxes(buf, ds, trakEnd, (t2, bs2, ds2) => {
        if (t2 !== 'mdhd') return;
        const ver     = buf[ds2];
        // v0: [version(1)][flags(3)][ctime(4)][mtime(4)][timescale(4)][duration(4)] = 20 bytes total header before language
        // v1: [version(1)][flags(3)][ctime(8)][mtime(8)][timescale(4)][duration(8)] = 32 bytes total header before language
        const langOff = ds2 + (ver === 1 ? 32 : 20);
        if (langOff + 2 <= buf.length) {
          const lcode = buf.readUInt16BE(langOff);
          if (lcode !== 0 && lcode !== 0x7fff) {
            const c1 = ((lcode >> 10) & 0x1f);
            const c2 = ((lcode >> 5)  & 0x1f);
            const c3 = (lcode         & 0x1f);
            // valid ISO-639 packed: each field in 1..26
            if (c1 >= 1 && c1 <= 26 && c2 >= 1 && c2 <= 26 && c3 >= 1 && c3 <= 26) {
              lang = String.fromCharCode(c1 + 0x60, c2 + 0x60, c3 + 0x60);
            }
          }
        }
      });
    });

    // ── mdia/hdlr (handler type) ──
    let hdlrType = '', hdlrName = '';
    walkBoxes(buf, trakDataStart, trakEnd, (t, bs, ds) => {
      if (t !== 'mdia') return;
      walkBoxes(buf, ds, trakEnd, (t2, bs2, ds2) => {
        if (t2 !== 'hdlr') return;
        // [version(1)][flags(3)][pre_defined(4)][handler_type(4)]...
        hdlrType = buf.toString('latin1', ds2 + 8, ds2 + 12);
        const nameEnd = buf.indexOf(0, ds2 + 24);
        hdlrName = (nameEnd > ds2 + 24 ? buf.slice(ds2 + 24, nameEnd) : buf.slice(ds2 + 24, ds2 + 48)).toString('utf8').trim();
      });
    });

    // ── stsd (codec, channels, sample rate) ──
    let codec = 'unknown', channels = 2, sampleRate = 44100;
    walkBoxes(buf, trakDataStart, trakEnd, (t, bs, ds) => {
      if (t !== 'mdia') return;
      walkBoxes(buf, ds, trakEnd, (t2, bs2, ds2) => {
        if (t2 !== 'minf') return;
        walkBoxes(buf, ds2, trakEnd, (t3, bs3, ds3) => {
          if (t3 !== 'stbl') return;
          walkBoxes(buf, ds3, trakEnd, (t4, bs4, ds4) => {
            if (t4 !== 'stsd') return;
            // stsd: [version(1)][flags(3)][entry_count(4)], then entries
            const eStart = ds4 + 8;
            // entry: [size(4)][type(4)][reserved(6)][data-ref-idx(2)] = 16 bytes header
            if (eStart + 8 <= buf.length) {
              codec = buf.toString('latin1', eStart + 4, eStart + 8);
              if (hdlrType === 'soun' && eStart + 28 <= buf.length) {
                // AudioSampleEntry: after 16-byte header: [reserved(8)][channelcount(2)][samplesize(2)][pre_defined(2)][reserved(2)][samplerate(4)]
                channels   = buf.readUInt16BE(eStart + 24) || 2;
                sampleRate = buf.readUInt32BE(eStart + 28) >> 16 || 44100;
              }
            }
          });
        });
      });
    });

    // ── record track ──
    if (hdlrType === 'soun') {
      const codecLabel   = { 'mp4a': 'AAC', 'ac-3': 'AC3', 'ec-3': 'E-AC3', 'dtsc': 'DTS', 'dtse': 'DTS-Express', 'dtsh': 'DTS-HD', 'alac': 'ALAC', 'opus': 'Opus' }[codec] || codec.toUpperCase();
      const channelLabel = channels === 1 ? '单声道' : channels === 2 ? '立体声' : `${channels}声道`;
      const langLabel    = getLangLabel(lang);
      // Use hdlrName if meaningful (not the generic "SoundHandler"), otherwise build a label
      const genericNames = ['soundhandler', 'sound handler', ''];
      const isGenericName = genericNames.includes((hdlrName || '').toLowerCase());
      const index = audioTracks.length;
      // tkhd flag bit 0 = track enabled. flags=3 → enabled; flags=2 → disabled (alt track)
      const isEnabled = (trakFlags & 0x1) !== 0;
      let trackTitle;
      if (!isGenericName && hdlrName) {
        trackTitle = hdlrName;
      } else {
        // Build meaningful name from lang label + index
        trackTitle = `${langLabel} (${channelLabel})`;
      }

      audioTracks.push({
        index, trackId,
        name: trackTitle,
        desc: `${langLabel}, ${channelLabel}, ${codecLabel}`,
        codec: codecLabel, channels, sampleRate, lang,
        flags: trakFlags,
        enabled: isEnabled,
        moovTrakOffset: trakStart - moovDataStart,
        trakSize: trakEnd - trakStart,
        tkhdEnabledByteOffset,
      });
    } else if (hdlrType === 'sbtl' || hdlrType === 'text' || hdlrType === 'subt') {
      const langLabel  = getLangLabel(lang);
      const trackTitle = hdlrName || `内置 ${langLabel} 字幕 ${subtitleTracks.length + 1}`;
      subtitleTracks.push({
        index: subtitleTracks.length, trackId,
        name: trackTitle,
        desc: `内置 ${langLabel} 字幕`,
        lang,
      });
    }
  });

  // Post-process: if all audio tracks have identical names, disambiguate
  if (audioTracks.length > 1) {
    const allSame = audioTracks.every(t => t.name === audioTracks[0].name);
    if (allSame) {
      audioTracks.forEach((t, i) => {
        const defaultTag = t.enabled ? ' [默认]' : '';
        t.name = `音轨 ${i + 1}: ${t.name}${defaultTag}`;
      });
    }
  }

  return { container: 'mp4', moovOffset: moovStart, moovSize: moovEnd - moovStart, audioTracks, subtitleTracks };
}

/**
 * Attempt to parse MP4 track metadata.
 * Handles: moov-first (small), moov-first (large, needs bigger fetch),
 *          mdat-first (moov at end of file), and Quark 8-byte prefix.
 */
async function parseMp4Metadata(buffer, targetUrl, headers) {
  if (buffer.length < 16) return null;

  // Detect Quark 8-byte prefix (starts with PNG magic 0x89504E47 but actually a prefix)
  let offset = 0;
  const firstFour = buffer.toString('hex', 0, 4);
  if (firstFour === '89504e47') offset = 8;  // Quark crypto prefix

  let buf = offset > 0 ? buffer.slice(offset) : buffer;

  // Must start with ftyp, moov, or wide
  if (buf.length < 8) return null;
  const topType = buf.toString('latin1', 4, 8);
  if (topType !== 'ftyp' && topType !== 'moov' && topType !== 'wide') return null;

  // Find moov — read the raw declared size from the box header (not capped at buf.length)
  let moovPos = -1, moovDeclaredSize = 0;
  {
    let p = 0;
    while (p + 8 <= buf.length) {
      const rawSize = buf.readUInt32BE(p);
      const type    = buf.toString('latin1', p + 4, p + 8);
      let   size    = rawSize;
      if (rawSize === 1 && p + 16 <= buf.length) size = Number(buf.readBigUInt64BE(p + 8));
      if (rawSize === 0) size = buf.length - p;
      if (type === 'moov') { moovPos = p; moovDeclaredSize = size; break; }
      if (size < 8) break;
      // Advance; if box extends beyond buffer but it's not moov, skip to keep looking
      if (p + size > buf.length) break;
      p += size;
    }
  }

  // Case A: moov found and fully in buffer
  if (moovPos !== -1 && moovPos + moovDeclaredSize <= buf.length) {
    return parseMoovBuffer(buf);
  }

  if (!targetUrl) return null;

  // Case B: moov found but truncated — fetch exact range
  if (moovPos !== -1 && moovDeclaredSize > 0 && moovDeclaredSize <= 32 * 1024 * 1024) {
    const need = offset + moovPos + moovDeclaredSize + 32;
    const bigger = await fetchByteRange(targetUrl, headers, 0, need);
    const stripped = offset > 0 ? bigger.slice(offset) : bigger;
    return parseMoovBuffer(stripped);
  }

  // Case C: moov not in first 512KB — check if mdat-first layout
  // Walk top-level boxes to find mdat and compute moov start
  let mdatStart = -1, mdatLargeSize = 0n, mdatNormalSize = 0;
  let ftypSize = 0;
  walkBoxes(buf, 0, buf.length, (type, bs, ds, be) => {
    if (type === 'ftyp') ftypSize = be - bs;
    if (type === 'mdat') {
      mdatStart = bs;
      const sizeField = buf.readUInt32BE(bs);
      if (sizeField === 1) {
        // 64-bit extended size
        mdatLargeSize = buf.readBigUInt64BE(bs + 8);
      } else {
        mdatNormalSize = sizeField;
      }
    }
  });

  if (mdatStart !== -1) {
    let moovFileStart;
    if (mdatLargeSize > 0n) {
      moovFileStart = offset + mdatStart + Number(mdatLargeSize);
    } else if (mdatNormalSize > 0) {
      moovFileStart = offset + mdatStart + mdatNormalSize;
    }

    if (moovFileStart !== undefined && moovFileStart > 0) {
      // Get total file size to know how much to fetch
      try {
        const headBuf = await fetchByteRange(targetUrl, headers, 0, 0);
        // We can't easily get Content-Length from fetchByteRange; use a HEAD trick via fetch
        // Instead, estimate: moov is typically < 20MB, fetch from moovFileStart to end capped at 20MB
        const moovBuf = await fetchByteRange(targetUrl, headers, moovFileStart, moovFileStart + 20 * 1024 * 1024);
        if (moovBuf.length > 8) {
          return parseMoovBuffer(moovBuf);
        }
      } catch {}
    }
  }

  // Case D: last resort, try 8MB from start
  try {
    const bigBuf = await fetchByteRange(targetUrl, headers, 0, 8 * 1024 * 1024);
    const stripped = offset > 0 ? bigBuf.slice(offset) : bigBuf;
    const meta = parseMoovBuffer(stripped);
    if (meta) return meta;
  } catch {}

  return null;
}

// ─── EBML / MKV parser ────────────────────────────────────────────────────────

function readEbmlVint(buf, pos) {
  if (pos >= buf.length) return { value: 0, len: 0 };
  const b = buf[pos];
  let len = 0, mask = 0;
  if      (b & 0x80) { len = 1; mask = 0x7f; }
  else if (b & 0x40) { len = 2; mask = 0x3f; }
  else if (b & 0x20) { len = 3; mask = 0x1f; }
  else if (b & 0x10) { len = 4; mask = 0x0f; }
  else if (b & 0x08) { len = 5; mask = 0x07; }
  else if (b & 0x04) { len = 6; mask = 0x03; }
  else if (b & 0x02) { len = 7; mask = 0x01; }
  else if (b & 0x01) { len = 8; mask = 0x00; }
  else return { value: 0, len: 0 };
  if (pos + len > buf.length) return { value: 0, len: 0 };
  let value = (buf[pos] & mask);
  // Use multiplication by 256 to prevent 32-bit signed bitwise overflow (e.g. video files > 2GB)
  for (let i = 1; i < len; i++) value = (value * 256) + buf[pos + i];
  return { value, len };
}

function readEbmlId(buf, pos) {
  if (pos >= buf.length) return { id: 0, len: 0 };
  const b = buf[pos];
  let len = 0;
  if      (b & 0x80) len = 1;
  else if (b & 0x40) len = 2;
  else if (b & 0x20) len = 3;
  else if (b & 0x10) len = 4;
  else return { id: 0, len: 0 };
  if (pos + len > buf.length) return { id: 0, len: 0 };
  let id = 0;
  for (let i = 0; i < len; i++) id = (id * 256) + buf[pos + i];
  return { id, len };
}

function parseMkvMetadata(buf) {
  // Verify EBML magic 0x1A45DFA3
  if (buf.length < 4) return null;
  const { id: ebmlId } = readEbmlId(buf, 0);
  if (ebmlId !== 0x1a45dfa3) return null;

  const audioTracks    = [];
  const subtitleTracks = [];

  let pos = 0;
  while (pos < buf.length - 4 && pos >= 0) {
    const { id, len: idLen } = readEbmlId(buf, pos);
    if (!idLen) break;
    pos += idLen;
    const sz = readEbmlVint(buf, pos);
    if (!sz.len) break;
    pos += sz.len;
    const dataEnd = (sz.value > 0) ? Math.min(buf.length, pos + sz.value) : buf.length;
    if (dataEnd <= pos) break;

    if (id === 0x18538067) { // Segment
      let spos = pos;
      while (spos < dataEnd - 4 && spos >= 0) {
        const { id: sid, len: sil } = readEbmlId(buf, spos);
        if (!sil) break;
        spos += sil;
        const ssz = readEbmlVint(buf, spos);
        if (!ssz.len) break;
        spos += ssz.len;
        const sEnd = (ssz.value > 0) ? Math.min(buf.length, spos + ssz.value) : dataEnd;
        if (sEnd <= spos) break;

        if (sid === 0x1654ae6b) { // Tracks
          let tpos = spos;
          while (tpos < sEnd - 4 && tpos >= 0) {
            const { id: tid, len: til } = readEbmlId(buf, tpos);
            if (!til) break;
            const entryStart = tpos;
            tpos += til;
            const tsz = readEbmlVint(buf, tpos);
            if (!tsz.len) break;
            tpos += tsz.len;
            const tEnd = (tsz.value > 0) ? Math.min(buf.length, tpos + tsz.value) : sEnd;
            if (tEnd <= tpos) break;

            if (tid === 0xae) { // TrackEntry
              let trackType = 0, trackNum = 0, codecId = '', trackName = '', trackLang = 'und', channels = 2;
              let flagDefault = 1, flagEnabled = 1;
              let flagDefaultOffset = -1, flagEnabledOffset = -1;
              const entryEnd   = tEnd;

              let epos = tpos;
              while (epos < tEnd - 2 && epos >= 0) {
                const { id: eid, len: eil } = readEbmlId(buf, epos);
                if (!eil) break;
                epos += eil;
                const esz = readEbmlVint(buf, epos);
                if (!esz.len) break;
                epos += esz.len;
                const eEnd = (esz.value > 0) ? Math.min(buf.length, epos + esz.value) : tEnd;
                if (eEnd <= epos) break;
                const val  = buf.slice(epos, eEnd);

                if      (eid === 0x83)    trackType = val[0] || 0;
                else if (eid === 0xd7)    trackNum  = val.readUIntBE(0, Math.min(val.length, 4));
                else if (eid === 0x88)    { flagDefault = val[0] || 0; flagDefaultOffset = epos; }
                else if (eid === 0xb9)    { flagEnabled = val[0] || 0; flagEnabledOffset = epos; }
                else if (eid === 0x86)    codecId   = val.toString('ascii');
                else if (eid === 0x536e)  trackName = val.toString('utf8');
                else if (eid === 0x22b59c) trackLang = val.toString('ascii').replace(/\0/g, '').trim();
                else if (eid === 0xe1) { // Audio
                  let apos = epos;
                  while (apos < eEnd - 2 && apos >= 0) {
                    const { id: aid, len: ail } = readEbmlId(buf, apos);
                    if (!ail) break;
                    apos += ail;
                    const asz = readEbmlVint(buf, apos);
                    if (!asz.len) break;
                    apos += asz.len;
                    if (aid === 0x9f) channels = buf.slice(apos, apos + asz.value).readUIntBE(0, Math.min(asz.value, 4));
                    apos += asz.value;
                  }
                }
                epos = eEnd;
              }

              if (trackType === 2) { // audio
                const langLabel    = getLangLabel(trackLang);
                const codecLabel   = codecId.replace(/^A_/, '');
                const channelLabel = channels === 1 ? '单声道' : channels === 2 ? '立体声' : `${channels}声道`;
                const index        = audioTracks.length;
                audioTracks.push({
                  index, trackId: trackNum,
                  name: trackName || `${langLabel} (${channelLabel})`,
                  desc: `${langLabel}, ${channelLabel}, ${codecLabel}`,
                  codec: codecLabel, channels, lang: trackLang,
                  enabled: flagEnabled !== 0,
                  entryStart,
                  entryEnd,
                  entrySize: entryEnd - entryStart,
                  flagEnabledOffset,
                  flagDefaultOffset,
                });
              } else if (trackType === 17) { // subtitle
                const langLabel = getLangLabel(trackLang);
                subtitleTracks.push({
                  index: subtitleTracks.length, trackId: trackNum,
                  name: trackName || `内置 ${langLabel} 字幕 ${subtitleTracks.length + 1}`,
                  desc: `内置 ${langLabel} 字幕`,
                  lang: trackLang,
                });
              }
            }
            tpos = tEnd;
          }
          break; // found Tracks, done
        }
        spos = sEnd;
      }
    }
    pos = dataEnd;
  }

  if (!audioTracks.length && !subtitleTracks.length) return null;

  // Disambiguate when names are identical
  if (audioTracks.length > 1) {
    const allSame = audioTracks.every(t => t.name === audioTracks[0].name);
    if (allSame) {
      audioTracks.forEach((t, i) => {
        const defaultTag = t.enabled ? ' [默认]' : '';
        t.name = `音轨 ${i + 1}: ${t.name}${defaultTag}`;
      });
    }
  }

  return { container: 'mkv', audioTracks, subtitleTracks };
}

// ─── MKV audio-track swap ─────────────────────────────────────────────────────

/**
 * Force audio track[targetTrackIndex] to be the primary one in an MKV file.
 * 1) Rewrite FlagEnabled / FlagDefault (if elements exist) so target is enabled
 * 2) Move target TrackEntry element to position 0 among audio tracks so Chromium/FFmpeg
 *    and Gecko demuxers both pick it as the primary audio stream.
 */
function swapMkvAudioTrack(buffer, targetTrackIndex) {
  if (targetTrackIndex == null || targetTrackIndex < 0) return buffer;
  const meta = parseMkvMetadata(buffer);
  if (!meta || meta.audioTracks.length < 2) return buffer;
  if (targetTrackIndex >= meta.audioTracks.length) return buffer;

  let out = Buffer.from(buffer);

  // 1. Rewrite enabled / default flags
  for (let i = 0; i < meta.audioTracks.length; i++) {
    const t = meta.audioTracks[i];
    if (t.flagEnabledOffset !== -1 && t.flagEnabledOffset < out.length) {
      out[t.flagEnabledOffset] = (i === targetTrackIndex) ? 1 : 0;
    }
    if (t.flagDefaultOffset !== -1 && t.flagDefaultOffset < out.length) {
      out[t.flagDefaultOffset] = (i === targetTrackIndex) ? 1 : 0;
    }
  }

  // 2. Move target audio track entry to position 0 among audio tracks
  if (targetTrackIndex > 0) {
    const t0 = meta.audioTracks[0];
    const tN = meta.audioTracks[targetTrackIndex];

    if (t0.entryEnd > out.length || tN.entryEnd > out.length) return buffer;

    const before = out.slice(0, t0.entryStart);
    const tN_buf = out.slice(tN.entryStart, tN.entryEnd);
    const between = out.slice(t0.entryEnd, tN.entryStart);
    const t0_buf = out.slice(t0.entryStart, t0.entryEnd);
    const after = out.slice(tN.entryEnd);

    out = Buffer.concat([before, tN_buf, between, t0_buf, after]);
  }

  return out;
}

// ─── MP4 audio-track swap ─────────────────────────────────────────────────────

/**
 * Force audio track[targetTrackIndex] to be the one the browser plays.
 * Two engines select the primary audio track differently:
 *   - Chromium (Edge/Chrome) plays the track whose tkhd "enabled" flag is set (bit 0 of flags).
 *   - Gecko (Firefox/Zen) plays the FIRST audio trak in the moov, ignoring the flag.
 * So we (1) set the enabled flag only on the target track and clear it on all others,
 * and (2) swap the target trak to the front so it is first. This makes both engines agree.
 *
 * The buffer must contain the full moov (and all audio trak boxes). Only the moov
 * region is modified in place; mdat/stco offsets are absolute and remain valid.
 */
function swapMp4AudioTrack(buffer, targetTrackIndex) {
  if (targetTrackIndex == null || targetTrackIndex < 0) return buffer;

  const meta = parseMoovBuffer(buffer);
  if (!meta || meta.audioTracks.length < 2) return buffer;
  if (targetTrackIndex >= meta.audioTracks.length) return buffer;

  const moovDataStart = meta.moovOffset + 8;
  let out = Buffer.from(buffer);

  // 1. Rewrite enabled flags: only the target audio track stays enabled.
  for (let i = 0; i < meta.audioTracks.length; i++) {
    const t = meta.audioTracks[i];
    const off = t.tkhdEnabledByteOffset;
    if (off == null || off < 0 || off >= out.length) continue;
    if (i === targetTrackIndex) out[off] |= 0x01;   // enable
    else                       out[off] &= 0xfe;    // disable
  }

  // 2. Move the target trak to the front (Gecko plays the first audio trak).
  if (targetTrackIndex > 0) {
    const t0 = meta.audioTracks[0];
    const tN = meta.audioTracks[targetTrackIndex];
    const abs0start = moovDataStart + t0.moovTrakOffset;
    const abs0end   = abs0start + t0.trakSize;
    const absNstart = moovDataStart + tN.moovTrakOffset;
    const absNend   = absNstart + tN.trakSize;

    if (abs0end > out.length || absNend > out.length) return buffer;

    const before   = out.slice(0, abs0start);
    const tN_buf   = out.slice(absNstart, absNend);
    const between  = out.slice(abs0end, absNstart);
    const t0_buf   = out.slice(abs0start, abs0end);
    const after    = out.slice(absNend);
    out = Buffer.concat([before, tN_buf, between, t0_buf, after]);
  }

  return out;
}

// ─── top-level inspector (MP4 + MKV + HLS) ────────────────────────────────────

async function inspectMediaTracks(targetUrl, headers) {
  const url  = String(targetUrl || '').trim();
  const hdrs = headers || {};

  // 1. HLS master playlist (.m3u8)
  if (url.toLowerCase().includes('.m3u8') || url.toLowerCase().includes('mpegurl')) {
    const audioTracks    = [];
    const subtitleTracks = [];
    try {
      const buf  = await fetchByteRange(url, hdrs, 0, 131072);
      const body = buf.toString('utf8');
      for (const line of body.split('\n')) {
        const t = line.trim();
        if (t.startsWith('#EXT-X-MEDIA:')) {
          const typeM = t.match(/TYPE=([A-Z-]+)/);
          if (!typeM) continue;
          const nameM = t.match(/NAME="([^"]+)"/);
          const langM = t.match(/LANGUAGE="([^"]+)"/);
          const lang  = langM ? langM[1] : 'und';
          const name  = nameM ? nameM[1] : getLangLabel(lang);
          if (typeM[1] === 'AUDIO')     audioTracks.push({ index: audioTracks.length,    name, desc: getLangLabel(lang) + ', AAC',       lang, codec: 'AAC' });
          if (typeM[1] === 'SUBTITLES') subtitleTracks.push({ index: subtitleTracks.length, name, desc: '内置 ' + getLangLabel(lang) + ' 字幕', lang });
        }
      }
    } catch {}
    return { container: 'hls', audioTracks, subtitleTracks };
  }

  // 2. MP4 / MKV direct stream
  try {
    const buf = await fetchByteRange(url, hdrs, 0, 524288); // 512 KB first pass
    if (!buf || buf.length < 8) return { container: 'unknown', audioTracks: [], subtitleTracks: [] };

    // Detect Quark 8-byte prefix
    const hasQuarkPrefix = (buf.toString('hex', 0, 4) === '89504e47');
    const stripped = hasQuarkPrefix ? buf.slice(8) : buf;

    const topType = stripped.length >= 8 ? stripped.toString('latin1', 4, 8) : '';
    if (topType === 'ftyp' || topType === 'moov' || topType === 'wide') {
      const meta = await parseMp4Metadata(buf, url, hdrs);
      return meta || { container: 'mp4', audioTracks: [], subtitleTracks: [] };
    }

    // MKV: EBML magic 0x1A 0x45 0xDF 0xA3
    if (stripped[0] === 0x1a && stripped[1] === 0x45 && stripped[2] === 0xdf && stripped[3] === 0xa3) {
      let meta = parseMkvMetadata(stripped);
      if (!meta) {
        const bigBuf = await fetchByteRange(url, hdrs, 0, 2 * 1024 * 1024);
        meta = parseMkvMetadata(hasQuarkPrefix ? bigBuf.slice(8) : bigBuf);
      }
      return meta || { container: 'mkv', audioTracks: [], subtitleTracks: [] };
    }

    return { container: 'unknown', audioTracks: [], subtitleTracks: [] };
  } catch (err) {
    return { container: 'unknown', error: err.message, audioTracks: [], subtitleTracks: [] };
  }
}

module.exports = { fetchByteRange, parseMoovBuffer, swapMp4AudioTrack, swapMkvAudioTrack, inspectMediaTracks };
