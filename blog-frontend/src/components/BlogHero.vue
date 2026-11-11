<script setup>
defineProps({ keyword: { type:String, default:"" } });
const emit = defineEmits(["update:keyword"]);
</script>
<template>
  <section class="sky-hero" aria-labelledby="hero-title">
    <div class="sky-hero__inner">
      <div class="sky-hero__copy">
        <p class="sky-hero__eyebrow">✦ &nbsp; AI INFRA · GPU COMPUTING · ENGINEERING</p>
        <h1 id="hero-title">让复杂系统<span>变得清晰。</span></h1>
        <p class="sky-hero__intro">你好，我是 Wynne。这里记录 GPU 并行计算、大模型推理系统、零知识证明，以及值得反复探索的工程问题。</p>
        <label class="sky-hero__search">
          <span aria-hidden="true">⌕</span>
          <span class="sr-only">搜索技术文章</span>
          <input :value="keyword" type="search" placeholder="搜索技术文章和工程笔记…" @input="emit('update:keyword',$event.target.value)" />
          <a href="#articles" aria-label="跳转到文章列表">↗</a>
        </label>
        <div class="sky-hero__topics"><span>CUDA</span><span>LLM Inference</span><span>Multi-GPU</span><span>ZKP</span></div>
      </div>
      <div class="sky-hero__art" aria-hidden="true">
        <div class="sky-hero__orbit sky-hero__orbit--one"></div>
        <div class="sky-hero__orbit sky-hero__orbit--two"></div>
        <span class="sky-hero__spark sky-hero__spark--one">✳</span><span class="sky-hero__spark sky-hero__spark--two">✦</span><span class="sky-hero__spark sky-hero__spark--three">✳</span>
        <div class="sky-hero__chip">
          <div class="sky-hero__chip-head"><span>WYNNE / SYSTEMS</span><i></i></div>
          <div class="sky-hero__grid"><span v-for="i in 35" :key="i"></span></div>
          <div class="sky-hero__chip-foot"><span>GPU · KERNELS</span><span>01 / 35</span></div>
        </div>
        <div class="sky-hero__floating sky-hero__floating--left">● Parallelism</div>
        <div class="sky-hero__floating sky-hero__floating--right">Latency ↓</div>
      </div>
    </div>
    <svg class="sky-hero__wave" viewBox="0 0 1440 125" preserveAspectRatio="none" aria-hidden="true"><path fill="#d5edfb" d="M0,67 C120,16 218,26 341,70 C465,112 545,10 690,40 C818,66 888,123 1041,66 C1162,21 1300,22 1440,75 L1440,125 L0,125Z"/><path fill="#fff" d="M0,83 C180,148 312,112 440,94 C572,75 676,128 807,96 C986,51 1103,118 1244,82 C1310,66 1380,68 1440,84 L1440,125 L0,125Z"/></svg>
  </section>
</template>
<style scoped>
.sky-hero { position:relative; isolation:isolate; overflow:hidden; margin:0; min-height:410px; padding:52px 0 102px; background:linear-gradient(150deg,#a8daf2 4%,#c9edfc 58%,#b7daf4 100%); color:#172a4c; }
.sky-hero__inner { width:min(1216px,92vw); margin:auto; display:grid; grid-template-columns:minmax(0,1.15fr) minmax(350px,.85fr); align-items:center; gap:30px; position:relative; z-index:2; }
.sky-hero__eyebrow { margin:0 0 18px; font:800 11px/1.6 "Space Grotesk",sans-serif; letter-spacing:.14em; color:#44659f; }
.sky-hero h1 { max-width:650px; margin:0; font-size:clamp(38px,4.5vw,66px); line-height:1.18; font-weight:800; letter-spacing:-.065em; text-wrap:balance; }
.sky-hero h1 span { color:#523aab; display:block; }
.sky-hero__intro { max-width:570px; font-size:15px; line-height:1.95; color:#355b79; margin:20px 0 24px; font-weight:500; }
.sky-hero__search { display:flex; align-items:center; gap:10px; padding:7px 9px 7px 17px; width:min(460px,100%); background:#fff; border:1px solid #a9c9e4; border-radius:14px; box-shadow:0 7px 0 rgba(64,116,166,.1),0 12px 28px rgba(32,83,129,.1); }
.sky-hero__search > span:first-child { font-size:26px; color:#8267b3; line-height:1; }
.sky-hero__search input { min-width:0; flex:1; border:0; background:transparent; outline:0; color:#213550; font:500 13px "DM Sans","Noto Sans SC",sans-serif; }
.sky-hero__search:focus-within { outline:3px solid #a28ddd; outline-offset:2px; }
.sky-hero__search a { display:grid; place-items:center; width:35px; height:35px; background:#5740b4; color:#fff; border-radius:9px; font-size:19px; }
.sky-hero__topics { display:flex; flex-wrap:wrap; gap:8px; margin-top:21px; }
.sky-hero__topics span { background:rgba(255,255,255,.25); color:#3c5a79; border:1px solid rgba(29,78,116,.2); border-radius:99px; padding:5px 11px; font:700 10px/1.5 "Space Grotesk",sans-serif; }
.sky-hero__art { position:relative; width:min(100%,490px); height:305px; display:grid; place-items:center; margin:auto; }
.sky-hero__orbit { position:absolute; border:2px dashed rgba(65,72,166,.22); border-radius:50%; transform:rotate(-12deg); }
.sky-hero__orbit--one { width:400px; height:255px; }
.sky-hero__orbit--two { width:340px; height:210px; transform:rotate(15deg); border-color:rgba(255,255,255,.72); }
.sky-hero__chip { width:287px; padding:18px; background:linear-gradient(150deg,#2e417e,#1a2456); border:3px solid #344488; border-radius:24px; transform:rotate(-7deg); box-shadow:10px 14px 0 #81afd7,15px 24px 34px rgba(26,58,116,.22); }
.sky-hero__chip-head,.sky-hero__chip-foot { display:flex; justify-content:space-between; align-items:center; color:#d1ddff; letter-spacing:.11em; font:700 10px/1.5 "Space Grotesk",sans-serif; }
.sky-hero__chip-head i { width:9px; height:9px; border-radius:50%; background:#9bea9e; box-shadow:0 0 11px #9bea9e; }
.sky-hero__grid { display:grid; grid-template-columns:repeat(7,1fr); gap:7px; margin:16px 0; }
.sky-hero__grid span { aspect-ratio:1; border-radius:6px; background:#9287f5; border:1px solid rgba(255,255,255,.25); box-shadow:inset 0 -3px 0 rgba(0,0,0,.17); }
.sky-hero__grid span:nth-child(7n + 1),.sky-hero__grid span:nth-child(7n + 4){background:#ffc1d9}
.sky-hero__grid span:nth-child(6n + 2){background:#9ee8ed}
.sky-hero__grid span:nth-child(8n){background:#ffdc83}
.sky-hero__floating { position:absolute; z-index:3; background:#fff; border:1px solid #a9bdf0; color:#39347d; padding:12px 14px; border-radius:12px; box-shadow:5px 6px 0 rgba(47,69,143,.1); font:800 12px "Space Grotesk",sans-serif; }
.sky-hero__floating--left{top:40px;left:0;transform:rotate(-8deg);color:#344c84}
.sky-hero__floating--right{bottom:28px;right:0;transform:rotate(7deg)}
.sky-hero__spark{position:absolute;font-size:32px}
.sky-hero__spark--one{top:15px;right:55px;color:#e34685}
.sky-hero__spark--two{bottom:10px;left:50px;color:#e7ae3b}
.sky-hero__spark--three{top:90px;right:5px;color:#7854cd}
.sky-hero__wave{display:block;position:absolute;bottom:-1px;left:0;width:100%;height:116px;z-index:1;pointer-events:none}
@media(max-width:940px){.sky-hero__inner{grid-template-columns:1fr}.sky-hero__art{position:absolute;right:-14vw;top:0;transform:scale(.75);opacity:.24;z-index:-1}.sky-hero__copy{z-index:3}}
@media(max-width:600px){.sky-hero{min-height:0;padding:45px 0 96px}.sky-hero h1{font-size:clamp(36px,9vw,48px)}.sky-hero__eyebrow{font-size:10px;letter-spacing:.07em}.sky-hero__intro{font-size:14px}.sky-hero__art{display:none}.sky-hero__wave{height:80px}}
</style>