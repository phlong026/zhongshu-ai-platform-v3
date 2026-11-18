// 本地账簿存储键单源（体检复用项4：64处硬编码字符串收敛入口）
// 各模型模块改从此处取键；页面一律经模型层访问，禁止直调 wx.*Storage 与裸键字符串。
const keys = {
  account: 'zhongshu-account-v1',
  business: 'zhongshu-business-v3',
  management: 'zhongshu-management-v1',
  engineering: 'zhongshu-engineering-v1',
  growth: 'zhongshu-growth-v1',
  studio: 'zhongshu-studio',
  creationDraft: 'zhongshu-creation-draft',
  creationExtractPrefix: 'zhongshu-creation-extract-v1:',
  videoDraft: 'zhongshu-video-draft',
  financeView: 'zhongshu-finance-view-v1',
  ownerGoals: 'zhongshu-owner-goals-v1',
  localRecords: 'zhongshu-local-records',
  savedDesign: 'zhongshu-saved-design',
  engineeringDraft: 'zhongshu-engineering-new-draft',
  engineeringRecentPrefix: 'zhongshu-engineering-recent:',
  materialProjectPrefix: 'zhongshu-material-project:',
  designFavoritesPrefix: 'zhongshu-design-favorites:',
  designExplorePrefix: 'zhongshu-design-explore:',
  performanceTasks: 'zhongshu-performance-tasks-v1',
};
module.exports = keys;
