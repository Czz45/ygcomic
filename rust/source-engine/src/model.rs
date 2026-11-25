//! 书源数据模型 —— 对齐 legado（阅读3.0）书源 JSON 结构。
//! 只做「文本小说源」，含小说内嵌图片（正文里的 <img> 转 markdown 占位）。

use serde::{Deserialize, Serialize};

/// 一条书籍搜索结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchBook {
    pub name: String,
    pub author: String,
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub word_count: String,
    #[serde(default)]
    pub last_chapter: String,
    #[serde(default)]
    pub intro: String,
    #[serde(default)]
    pub cover_url: String,
    /// 详情页绝对 URL
    pub book_url: String,
    /// 来源书源名（合并结果时标注）
    #[serde(default)]
    pub source_name: String,
    #[serde(default)]
    pub source_url: String,
}

/// 书籍详情
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct BookDetail {
    pub name: String,
    pub author: String,
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub word_count: String,
    #[serde(default)]
    pub last_chapter: String,
    #[serde(default)]
    pub intro: String,
    #[serde(default)]
    pub cover_url: String,
    #[serde(default)]
    pub toc_url: String,
}

/// 目录中的一个章节
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Chapter {
    pub title: String,
    pub url: String,
    #[serde(default)]
    pub is_vip: bool,
    #[serde(default)]
    pub is_volume: bool,
}

/// 章节正文。text 为纯文本（图片以 \u{FFFC} 占位 + images 数组索引引用）
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Content {
    pub title: String,
    pub text: String,
    pub images: Vec<String>,
    pub next_url: String,
}

/// 发现/分类项
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExploreKind {
    pub title: String,
    pub url: String,
}
