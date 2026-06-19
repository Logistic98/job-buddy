CREATE TABLE IF NOT EXISTS job_buddy_blacklist_item (
  item_id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  item_type VARCHAR(32) NOT NULL,
  reason TEXT,
  source VARCHAR(32) NOT NULL DEFAULT 'system',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_job_buddy_blacklist_item_name_type
  ON job_buddy_blacklist_item (name, item_type);

INSERT INTO job_buddy_blacklist_item(item_id, name, item_type, reason, source, enabled) VALUES
  ('blk_company_chinasoft', '中软国际', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_isstech', '软通动力', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_pactera', '文思海辉', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_beyondsoft', '博彦科技', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_neusoft', '东软集团', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_fabernovel', '法本信息', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_bojun', '佰钧成', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_jiangsuhope', '润和软件', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_clover', '柯莱特', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_symbio', '信必优', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_archermind', '诚迈科技', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_sinosoft', '中科软', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_linkstec', '凌志软件', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_ecitic', '易诚互动', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_northking', '京北方', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_dcits', '神州信息', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_yusys', '宇信科技', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_gaoweida', '高伟达', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_agree', '赞同科技', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_hand', '汉得信息', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_hande', '汉得', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_longshine', '朗新科技', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_insigma', '浙大网新', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_teamsun', '华胜天成', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_transwarp_partner', '华钦科技', 'company', '常见外包/驻场/项目制供应商，默认屏蔽', 'system', TRUE),
  ('blk_company_icss', '华为外包', 'keyword', '岗位描述风险关键词，默认屏蔽', 'system', TRUE),
  ('blk_keyword_outsourcing', '外包', 'keyword', '岗位描述风险关键词，默认屏蔽', 'system', TRUE),
  ('blk_keyword_onsite', '驻场', 'keyword', '岗位描述风险关键词，默认屏蔽', 'system', TRUE),
  ('blk_keyword_dispatch', '劳务派遣', 'keyword', '岗位描述风险关键词，默认屏蔽', 'system', TRUE),
  ('blk_keyword_project_outsource', '项目外派', 'keyword', '岗位描述风险关键词，默认屏蔽', 'system', TRUE),
  ('blk_keyword_manpower', '人力外包', 'keyword', '岗位描述风险关键词，默认屏蔽', 'system', TRUE),
  ('blk_keyword_vendor', '供应商岗位', 'keyword', '岗位描述风险关键词，默认屏蔽', 'system', TRUE),
  ('blk_keyword_third_party', '第三方合同', 'keyword', '岗位描述风险关键词，默认屏蔽', 'system', TRUE)
ON CONFLICT (item_id) DO NOTHING;
