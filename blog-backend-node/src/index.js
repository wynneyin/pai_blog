const express = require('express');
const cors = require('cors');
const articles = require('./articles');

const app = express();
const PORT = process.env.PORT || 8080;
const ADMIN_TOKEN = process.env.BLOG_ADMIN_TOKEN || '';
const CORS_ORIGINS = (process.env.BLOG_CORS_ALLOWED_ORIGINS || 'http://localhost:5173')
  .split(',')
  .map(s => s.trim());

app.use(cors({ origin: CORS_ORIGINS }));
app.use(express.json());

function ok(data) {
  return { success: true, data, message: null };
}

function fail(status, message) {
  return { success: false, data: null, message };
}

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

app.listen(PORT, () => {
  console.log(`Blog backend listening on :${PORT}`);
  console.log(`ARTICLES_PATH=${process.env.ARTICLES_PATH || 'articles/'}`);
});
