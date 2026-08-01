const fs = require('fs');
const path = require('path');
const { parseFrontMatter, renderMarkdown, generateSlug, makeSummary } = require('./markdown');

const ARTICLES_PATH = process.env.ARTICLES_PATH || path.join(process.cwd(), 'articles');
const MAX_PAGE_SIZE = 50;

let cache = { marker: null, articles: [] };
const viewCounter = new Map();

function findMarkdownFiles(dir) {
  const results = [];
  if (!fs.existsSync(dir)) return results;

  function walk(current) {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory() && !entry.name.startsWith('_drafts')) {
        walk(full);
      } else if (entry.isFile() && entry.name.endsWith('.md') && !entry.name.startsWith('._')) {
        results.push(full);
      }
    }
  }

  walk(dir);
  return results.sort();
}

function calcMarker(files) {
  let marker = 17n;
  for (const file of files) {
    try {
      const stat = fs.statSync(file);
      marker = marker * 31n + BigInt(file.length);
      marker = marker * 31n + BigInt(stat.mtimeMs | 0);
      marker = marker * 31n + BigInt(stat.size);
    } catch (_) {}
  }
  return marker.toString();
}

function extractCategory(filePath) {
  const rel = path.relative(ARTICLES_PATH, filePath);
  const parts = rel.split(path.sep);
  return parts.length > 1 ? parts[0] : '默认';
}

function deduplicateSlug(slug, seen) {
  const count = seen.get(slug) || 0;
  seen.set(slug, count + 1);
  return count === 0 ? slug : `${slug}-${count + 1}`;
}

function loadArticles(files) {
  const loaded = [];
  const slugSeen = new Map();

  for (const file of files) {
    try {
      const raw = fs.readFileSync(file, 'utf-8');
      const fm = parseFrontMatter(raw);
      if (!fm.published) continue;

      const htmlContent = renderMarkdown(fm.body);
      const id = path.basename(file, '.md');
      const title = fm.title || id;
      const slug = deduplicateSlug(fm.slug || generateSlug(title), slugSeen);
      const category = fm.category || extractCategory(file);
      const stat = fs.statSync(file);
      const fileTime = stat.mtime.toISOString();
      const createdAt = fm.date || fileTime;

      loaded.push({
        id,
        slug,
        title,
        content: htmlContent,
        summary: fm.summary || makeSummary(fm.body),
        author: fm.author,
        category,
        tags: fm.tags,
        coverImage: fm.cover || '',
        published: true,
        createdAt,
        updatedAt: fileTime,
        views: viewCounter.get(slug) || 0,
      });
    } catch (e) {
      console.error(`Failed to load ${file}:`, e.message);
    }
  }

  return loaded.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
}

function getPublishedArticles() {
  const files = findMarkdownFiles(ARTICLES_PATH);
  const marker = calcMarker(files);
  if (marker === cache.marker) return cache.articles;

  const articles = loadArticles(files);
  cache = { marker, articles };
  console.log(`Reloaded articles, total=${articles.length}`);
  return articles;
}

function toListArticle(a) {
  const { content: _, ...rest } = a;
  return rest;
}

function paginate(list, page, pageSize) {
  const safePage = Math.max(page, 1);
  const safeSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
  const from = (safePage - 1) * safeSize;
  const to = Math.min(from + safeSize, list.length);
  const slice = from >= list.length ? [] : list.slice(from, to);
  const totalPages = list.length === 0 ? 0 : Math.ceil(list.length / safeSize);
  return {
    list: slice,
    pagination: { page: safePage, pageSize: safeSize, total: list.length, totalPages },
  };
}

const api = {
  getArticles(page, pageSize) {
    return paginate(getPublishedArticles().map(toListArticle), page, pageSize);
  },

  getArticleBySlug(slug) {
    const article = getPublishedArticles().find(a => a.slug.toLowerCase() === slug.toLowerCase());
    if (!article) throw Object.assign(new Error(`Article not found: ${slug}`), { status: 404 });
    const views = (viewCounter.get(article.slug) || 0) + 1;
    viewCounter.set(article.slug, views);
    return { ...article, views };
  },

  getArticlesByCategory(category, page, pageSize) {
    const filtered = getPublishedArticles()
      .filter(a => a.category.toLowerCase() === category.toLowerCase())
      .map(toListArticle);
    return paginate(filtered, page, pageSize);
  },

  getArticlesByTag(tag, page, pageSize) {
    const norm = tag.toLowerCase();
    const filtered = getPublishedArticles()
      .filter(a => a.tags.some(t => t.toLowerCase() === norm))
      .map(toListArticle);
    return paginate(filtered, page, pageSize);
  },

  searchArticles(keyword, page, pageSize) {
    const norm = keyword.toLowerCase();
    const matched = getPublishedArticles()
      .filter(a =>
        a.title.toLowerCase().includes(norm) ||
        a.summary.toLowerCase().includes(norm) ||
        a.content.toLowerCase().includes(norm)
      )
      .map(toListArticle);
    return paginate(matched, page, pageSize);
  },

  getCategories() {
    const counts = new Map();
    for (const a of getPublishedArticles()) {
      counts.set(a.category, (counts.get(a.category) || 0) + 1);
    }
    return [...counts.entries()]
      .map(([name, count]) => ({ name, count }))
      .sort((a, b) => b.count - a.count);
  },

  getTags() {
    const counts = new Map();
    for (const a of getPublishedArticles()) {
      for (const t of a.tags) counts.set(t, (counts.get(t) || 0) + 1);
    }
    return [...counts.entries()]
      .map(([name, count]) => ({ name, count }))
      .sort((a, b) => b.count - a.count);
  },

  clearCache() {
    cache = { marker: null, articles: [] };
  },
};

module.exports = api;
