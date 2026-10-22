# ShiftBalance 前端

排班管理工作台，使用 React、TypeScript、Vite 和 React Bits。当前页面读取 Spring Boot API 的人员、岗位、轮班模板、日期需求和候选排班。

## 本地运行

```bash
npm install
npm run dev
```

默认将 `/api` 代理到 `http://localhost:8080`。先启动后端；若后端地址不同，可复制 `.env.example` 为 `.env.local` 并修改：

```dotenv
SHIFTBALANCE_BACKEND_URL=http://localhost:8080
```

也可以在页面右上角“接口设置”填入 Spring Boot 根地址，例如 `http://localhost:8080`。使用直连地址时，后端需允许开发页面的跨域请求；留空则走 Vite 代理。若要换 API 根地址变量，设置 `VITE_API_BASE_URL`。

## 生产构建

```bash
npm run build
npm run preview
```

静态产物位于 `dist/`。生产环境需由反向代理将 `/api` 转发到后端，或设置 `VITE_API_BASE_URL` 后重新构建。

## 当前功能

- 查看二上二休和早下/晚下轮换日期；轮换锚点来自后端模板。
- 查看人员资格、每日需求、候选排班和监管人员。
- 调用数据库生成接口生成或重新生成候选排班。
- 调整岗位人员和候机楼监管人员，并将完整分配保存到候选排班。
- 按岗位资格、组别、需求人数、同段重复、回流重复和监管人员重复检查明显冲突。

候选排班 ID 及其需求快照保存在当前浏览器本地，以便在同一浏览器重开页面后读取。后端目前没有按日期查询最新候选的接口。发布、历史导入、Excel 导出、区间统计及其他管理页面尚未接入，因此界面会禁用这些入口。

## React Bits

`src/components/react-bits/FadeContent.tsx` 是通过 React Bits 官方 jsrepo 安装的 Fade Content 源码组件，用于当天概览卡片的轻量入场效果。它尊重系统的减少动态效果设置。
