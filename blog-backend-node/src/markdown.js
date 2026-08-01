const matter = require('gray-matter');
const { marked } = require('marked');

const SUMMARY_LENGTH = 180;

function parseFrontMatter(content) {
  const { data, content: body } = matter(content);
  return {
    title: data.title || '',
    slug: data.slug || '',
    category: data.category || '',
    author: data.author || '博主',
    cover: data.cover || '',
    summary: data.summary || '',
    published: data.published !== false,
    date: parseDate(data.date),
    tags: normalizeTags(data.tags),
    body,
  };
}

function normalizeTags(tags) {
  if (!tags) return [];
  if (Array.isArray(tags)) return tags.map(String);
  return String(tags).split(',').map(t => t.trim()).filter(Boolean);
}

function parseDate(value) {
  if (!value) return null;
  const d = new Date(value);
  return isNaN(d.getTime()) ? null : d.toISOString();
}

function renderMarkdown(md) {
  return marked.parse(md);
}

function generateSlug(title) {
  return title
    .toLowerCase()
    .replace(/[^a-z0-9\s-]/g, '')
    .replace(/[\s-]+/g, '-')
    .replace(/^-|-$/g, '');
}

function makeSummary(md) {
  const plain = md
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`/g, '')
    .replace(/#+/g, '')
    .replace(/>+/g, '')
    .replace(/\*+/g, '')
    .replace(/_+/g, '')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\s+/g, ' ')
    .trim();
  return plain.length <= SUMMARY_LENGTH ? plain : plain.slice(0, SUMMARY_LENGTH) + '...';
}

module.exports = { parseFrontMatter, renderMarkdown, generateSlug, makeSummary };
