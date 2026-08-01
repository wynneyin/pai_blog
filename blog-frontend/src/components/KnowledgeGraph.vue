<script setup>
import * as d3 from "d3";
import { onMounted, onUnmounted, ref, watch } from "vue";
import api from "../api/client";

const props = defineProps({
  currentSlug: { type: String, required: true },
});

const emit = defineEmits(["navigate"]);

const container = ref(null);
const loading = ref(true);
const graphData = ref(null);
let simulation = null;
let resizeObserver = null;

async function fetchGraph() {
  try {
    const res = await api.get("/graph");
    graphData.value = res.data;
  } catch {
    graphData.value = null;
  } finally {
    loading.value = false;
  }
}

function buildGraph(data) {
  if (!container.value || !data) return;

  const el = container.value;
  d3.select(el).selectAll("*").remove();

  const W = el.clientWidth || 260;
  const H = el.clientHeight || 300;

  const svg = d3
    .select(el)
    .append("svg")
    .attr("width", W)
    .attr("height", H)
    .style("background", "transparent");

  // zoom
  const g = svg.append("g");
  svg.call(
    d3.zoom()
      .scaleExtent([0.3, 3])
      .on("zoom", (e) => g.attr("transform", e.transform))
  );

  // filter to neighborhood: current article + its 1-hop neighbors
  const current = props.currentSlug;
  const neighborSlugs = new Set([current]);
  data.links.forEach((l) => {
    if (l.source === current || l.target === current) {
      neighborSlugs.add(l.source);
      neighborSlugs.add(l.target);
    }
  });

  const nodes = data.nodes
    .filter((n) => neighborSlugs.has(n.id))
    .map((n) => ({ ...n }));
  const nodeIds = new Set(nodes.map((n) => n.id));
  const links = data.links
    .filter((l) => nodeIds.has(l.source) && nodeIds.has(l.target))
    .map((l) => ({ ...l }));

  if (!nodes.length) return;

  // arrow marker
  svg
    .append("defs")
    .append("marker")
    .attr("id", "arrow")
    .attr("viewBox", "0 -4 8 8")
    .attr("refX", 14)
    .attr("refY", 0)
    .attr("markerWidth", 6)
    .attr("markerHeight", 6)
    .attr("orient", "auto")
    .append("path")
    .attr("d", "M0,-4L8,0L0,4")
    .attr("fill", "#555");

  simulation = d3
    .forceSimulation(nodes)
    .force(
      "link",
      d3
        .forceLink(links)
        .id((d) => d.id)
        .distance((l) => (l.type === "wikilink" ? 80 : 55))
    )
    .force("charge", d3.forceManyBody().strength(-120))
    .force("center", d3.forceCenter(W / 2, H / 2))
    .force("collision", d3.forceCollide(18));

  const link = g
    .append("g")
    .selectAll("line")
    .data(links)
    .join("line")
    .attr("stroke", (d) => (d.type === "wikilink" ? "#7c6ff7" : "#444"))
    .attr("stroke-width", (d) => (d.type === "wikilink" ? 1.5 : 1))
    .attr("stroke-dasharray", (d) => (d.type === "tag" ? "3,3" : null))
    .attr("marker-end", (d) => (d.type === "wikilink" ? "url(#arrow)" : null))
    .attr("opacity", 0.6);

  const node = g
    .append("g")
    .selectAll("g")
    .data(nodes)
    .join("g")
    .attr("cursor", (d) => (d.type === "article" ? "pointer" : "default"))
    .call(
      d3
        .drag()
        .on("start", (e, d) => {
          if (!e.active) simulation.alphaTarget(0.3).restart();
          d.fx = d.x;
          d.fy = d.y;
        })
        .on("drag", (e, d) => {
          d.fx = e.x;
          d.fy = e.y;
        })
        .on("end", (e, d) => {
          if (!e.active) simulation.alphaTarget(0);
          d.fx = null;
          d.fy = null;
        })
    )
    .on("click", (e, d) => {
      if (d.type === "article" && d.id !== current) {
        emit("navigate", d.id);
      }
    });

  node
    .append("circle")
    .attr("r", (d) => {
      if (d.id === current) return 10;
      if (d.type === "tag") return 5;
      return 7;
    })
    .attr("fill", (d) => {
      if (d.id === current) return "#7c6ff7";
      if (d.type === "tag") return "#3a3a4a";
      return "#2a2a3a";
    })
    .attr("stroke", (d) => {
      if (d.id === current) return "#a89cf7";
      if (d.type === "tag") return "#555";
      return "#666";
    })
    .attr("stroke-width", (d) => (d.id === current ? 2 : 1));

  node
    .append("text")
    .text((d) => {
      const label = d.type === "tag" ? `#${d.title}` : d.title;
      return label.length > 12 ? label.slice(0, 11) + "…" : label;
    })
    .attr("x", (d) => (d.type === "tag" ? 7 : 12))
    .attr("y", 4)
    .attr("font-size", (d) => (d.type === "tag" ? "10px" : "11px"))
    .attr("fill", (d) => (d.id === current ? "#c4b8ff" : "#aaa"))
    .attr("pointer-events", "none");

  simulation.on("tick", () => {
    link
      .attr("x1", (d) => d.source.x)
      .attr("y1", (d) => d.source.y)
      .attr("x2", (d) => d.target.x)
      .attr("y2", (d) => d.target.y);
    node.attr("transform", (d) => `translate(${d.x},${d.y})`);
  });
}

watch(
  () => props.currentSlug,
  async () => {
    loading.value = true;
    await fetchGraph();
    buildGraph(graphData.value);
  }
);

watch(graphData, (data) => {
  buildGraph(data);
});

onMounted(async () => {
  await fetchGraph();
  buildGraph(graphData.value);

  resizeObserver = new ResizeObserver(() => {
    if (graphData.value) buildGraph(graphData.value);
  });
  if (container.value) resizeObserver.observe(container.value);
});

onUnmounted(() => {
  simulation?.stop();
  resizeObserver?.disconnect();
});
</script>

<template>
  <div class="kg-wrapper">
    <h2>知识图谱</h2>
    <p v-if="loading" class="kg-status">加载中...</p>
    <p v-else-if="!graphData" class="kg-status">暂无数据</p>
    <div v-else ref="container" class="kg-canvas"></div>
    <p class="kg-hint">紫色节点为当前文章 · 虚线为 Tag · 实线为双链</p>
  </div>
</template>

<style scoped>
.kg-wrapper {
  margin-top: 1.5rem;
}

.kg-wrapper h2 {
  font-size: 0.85rem;
  font-weight: 600;
  color: #888;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 0.75rem;
}

.kg-canvas {
  width: 100%;
  height: 280px;
  background: #12121a;
  border-radius: 8px;
  border: 1px solid #2a2a3a;
  overflow: hidden;
}

.kg-status {
  font-size: 0.8rem;
  color: #666;
  text-align: center;
  padding: 2rem 0;
}

.kg-hint {
  font-size: 0.72rem;
  color: #555;
  margin-top: 0.4rem;
  line-height: 1.4;
}
</style>
