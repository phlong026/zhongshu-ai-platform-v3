import request from '@/config/axios'
import { config } from '@/config/axios/config'

export interface TrainingAttachment { versionId: string; download: boolean }
export interface TrainingLesson { id: string; title: string; chapter: string; mainVersionId: string; attachments: TrainingAttachment[]; completionPercent: number }
export interface TrainingCourse {
  title: string; category: string; lecturer: string; summary: string; objective: string; coverVersionId: string;
  allOrganizations: boolean; organizationIds: string[]; roles: string[]; userIds: string[];
  opensAt: string; closesAt: string; lessons: TrainingLesson[]; attachments: TrainingAttachment[]
}
export interface CourseDetail { id: string; revision: number; state: string; reviewState: string; activeRelease: string | null; content: TrainingCourse; history: Record<string, any>[] }
export interface TrainingMaterial { material_id: string; material_revision: number; name: string; category: string; version_id: string; version_no: number; file_name: string; extension: string; size_bytes: number; state: string; error: string; duration: number }
export interface TrainingOptions { categories: string[]; roles: string[]; organizations: { id: string; name: string }[]; chunkSize: number }
const base = '/training/admin'
export const trainingOptions = (): Promise<TrainingOptions> => request.get({ url: `${base}/options` })
export const listCourses = (keyword = '', page = 1): Promise<{ list: Record<string, any>[]; total: number }> => request.get({ url: `${base}/courses`, params: { keyword, page } })
export const getCourse = (id: string): Promise<CourseDetail> => request.get({ url: `${base}/courses/${id}` })
export const saveCourse = (id: string | undefined, content: TrainingCourse, expectedRevision?: number): Promise<CourseDetail> => id
  ? request.put({ url: `${base}/courses/${id}`, data: { content, expectedRevision } })
  : request.post({ url: `${base}/courses`, data: { content } })
export const checkCourse = (id: string): Promise<string[]> => request.get({ url: `${base}/courses/${id}/checks` })
export const courseAction = (id: string, action: string, expectedRevision: number, reason = ''): Promise<CourseDetail> => request.post({ url: `${base}/courses/${id}/${action}`, data: { expectedRevision, reason } })
export const listMaterials = (keyword = '', category = '', page = 1): Promise<{ list: TrainingMaterial[]; total: number }> => request.get({ url: `${base}/materials`, params: { keyword, category, page } })
export interface TrainingCategory { id: string; name: string; revision: number; material_count: number; system_default: boolean }
export const listMaterialCategories = (): Promise<TrainingCategory[]> => request.get({ url: `${base}/material-categories` })
export const createMaterialCategory = (name: string) => request.post({ url: `${base}/material-categories`, data: { name } })
export const renameMaterialCategory = (id: string, expectedRevision: number, name: string) => request.put({ url: `${base}/material-categories/${id}`, data: { expectedRevision, name } })
export const deleteMaterialCategory = (id: string, expectedRevision: number, expectedMaterialCount: number) => request.delete({ url: `${base}/material-categories/${id}`, data: { expectedRevision, expectedMaterialCount } })
export const getMaterial = (id: string): Promise<Record<string, any>> => request.get({ url: `${base}/materials/${id}` })
export const editTrainingMaterial = (id: string, data: { expectedRevision: number; name: string; category: string }) => request.put({ url: `${base}/materials/${id}`, data })
export const deleteTrainingMaterial = (id: string, expectedRevision: number) => request.delete({ url: `${base}/materials/${id}`, data: { expectedRevision } })
export const retryMaterial = (id: string) => request.post({ url: `${base}/materials/${id}/retry` })
export const materialTicket = async (id: string, purpose: string): Promise<string> => {
  const t = await request.post<{ path: string }>({ url: `${base}/materials/${id}/ticket`, params: { purpose } })
  return config.base_url.replace(/\/$/, '') + t.path
}
export const startUpload = (data: Record<string, unknown>): Promise<Record<string, any>> => request.post({ url: `${base}/uploads`, data })
export const getUpload = (id: string): Promise<Record<string, any>> => request.get({ url: `${base}/uploads/${id}` })
export const uploadPart = (id: string, part: number, data: Blob) => request.put({ url: `${base}/uploads/${id}/parts/${part}`, data, headersType: 'application/octet-stream', timeout: 120000 })
export const completeUpload = (id: string): Promise<Record<string, any>> => request.post({ url: `${base}/uploads/${id}/complete`, timeout: 120000 })
export const stateText = (state: string) => ({ READY: '可用', UPLOADING: '上传中', UPLOADED: '待处理', PROCESSING: '处理中', FAILED: '处理失败', DRAFT: '草稿', PENDING: '待审核', REJECTED: '已退回', PUBLISHED: '已发布', WITHDRAWN: '已下架' }[state] || state)
export const roleText: Record<string, string> = { sales: '业务员', 'sales-lead': '业务负责人', engineer: '工程人员', 'project-manager': '工程负责人', finance: '财务专员', 'finance-lead': '财务负责人', 'hq-design-admin': '总部设计行政', 'hq-designer': '总部设计师', 'hq-design-lead': '总部设计负责人', boss: '老板', owner: '门店负责人', 'hq-operations': '总部运营', 'hq-operations-lead': '总部运营负责人' }
export const newTrainingCourse = (): TrainingCourse => ({ title: '', category: 'AI短视频获客', lecturer: '', summary: '', objective: '', coverVersionId: '', allOrganizations: false, organizationIds: [], roles: [], userIds: [], opensAt: '', closesAt: '', lessons: [], attachments: [] })
