const express = require('express');
const cors = require('cors');
const articles = require('./articles');
const { relayRouter, relayAdminRouter, relayProxyRouter } = require('./relay');

const app = express();
const PORT = process.env.PORT || 8080;
const ADMIN_TOKEN = process.env.BLOG_ADMIN_TOKEN || '';
const CORS_ORIGINS = (process.env.BLOG_CORS_ALLOWED_ORIGINS || 'http://localhost:5173')
  .split(',')
  .map(s => s.trim());

app.use(cors({ origin: CORS_ORIGINS }));
// Keep the raw request bytes so /v1/chat/completions can forward the body
// untouched instead of re-serialising it. The relay proxy gets its own larger
// parser first: OpenAI clients routinely send long prompts and inline base64
// images past body-parser's 100kb default, and the body is forwarded verbatim.
// 25mb still keeps per-request memory bounded.
const PROXY_JSON_LIMIT = '25mb';

app.use(
  '/v1',
  express.json({
    limit: PROXY_JSON_LIMIT,
    verify: (req, _res, buf) => {
      req.rawBody = buf;
    }
  })
);
app.use(
  express.json({
    verify: (req, _res, buf) => {
      req.rawBody = buf;
    }
  })
);

function ok(data) {
  return { success: true, data, message: null };
}

function fail(status, message) {
  return { success: false, data: null, message };
}

app.use('/api/relay', relayRouter);
app.use('/api/admin/relay', relayAdminRouter);
app.use('/v1', relayProxyRouter);

app.get('/api/articles', (req, res) => {
  const page = parseInt(req.query.page) || 1;
  const pageSize = parseInt(req.query.pageSize) || 10;
  const { category, tag, keyword } = req.query;

  try {
    let result;
    if (keyword && keyword.trim()) {
      result = articles.searchArticles(keyword.trim(), page, pageSize);
    } else if (category && category.trim()) {
      result = articles.getArticlesByCategory(category.trim(), page, pageSize);
    } else if (tag && tag.trim()) {
      result = articles.getArticlesByTag(tag.trim(), page, pageSize);
    } else {
      result = articles.getArticles(page, pageSize);
    }
    res.json(ok(result));
  } catch (e) {
    res.status(e.status || 500).json(fail(e.status || 500, e.message));
  }
});

app.get('/api/articles/:slug', (req, res) => {
  try {
    res.json(ok(articles.getArticleBySlug(req.params.slug)));
  } catch (e) {
    res.status(e.status || 500).json(fail(e.status || 500, e.message));
  }
});

app.get('/api/categories', (_req, res) => {
  res.json(ok(articles.getCategories()));
});

app.get('/api/tags', (_req, res) => {
  res.json(ok(articles.getTags()));
});

app.post('/api/admin/cache/refresh', (req, res) => {
  if (ADMIN_TOKEN && req.headers['x-admin-token'] !== ADMIN_TOKEN) {
    return res.status(401).json(fail(401, 'Invalid admin token'));
  }
  articles.clearCache();
  res.json(ok('cache refreshed'));
});

function isOpenAIRequest(req) {
  const url = String(req.originalUrl || req.url || '').split('?')[0];
  return url === '/v1' || url.startsWith('/v1/');
}

// Malformed JSON bodies must answer with a JSON envelope, matching the Java
// backend's HttpMessageNotReadableException handler instead of Express' HTML page.
app.use((err, _req, res, next) => {
  if (res.headersSent) return next(err);
  if (err && err.type === 'entity.parse.failed') {
    if (isOpenAIRequest(_req)) {
      return res.status(400).json({
        error: {
          message: '请求体不是合法 JSON',
          type: 'invalid_request_error',
          code: 'invalid_json'
        }
      });
    }
    return res.status(400).json(fail(400, '请求参数不合法'));
  }
  if (err && err.type === 'entity.too.large') {
    if (isOpenAIRequest(_req)) {
      return res.status(413).json({
        error: {
          message: '请求体过大',
          type: 'invalid_request_error',
          code: 'request_too_large'
        }
      });
    }
    return res.status(413).json(fail(413, '请求体过大'));
  }
  const status = err && Number.isInteger(err.status) ? err.status : 500;
  const message = status === 500 ? '服务器内部错误' : err.message;
  res.status(status).json(fail(status, message));
});

app.listen(PORT, () => {
  console.log(`Blog backend listening on :${PORT}`);
  console.log(`ARTICLES_PATH=${process.env.ARTICLES_PATH || 'articles/'}`);
});
