import request from '@/config/axios'
import type { FranchiseOnboardResult } from './platform-org'

export interface MapRegion {
  code: string
  name: string
  level: 'province' | 'city' | 'district'
  parentCode: string | null
  provinceCode: string
  provinceName: string
  cityCode: string
  cityName: string
  districtCode: string
  districtName: string
  longitude: number | null
  latitude: number | null
}

export interface DirectoryItem {
  id: string
  name: string
  brand: string
  brandId: string
  brandName: string
  brandColor: string
  recordType: 'FRANCHISE' | 'COVERAGE'
  originalRegionText: string
  matchStatus: 'RESOLVED' | 'PARTIAL' | 'UNRESOLVED'
  regionCodes: string[]
  provinceCode: string | null
  cityCode: string | null
  province: string
  city: string
  area: string
  districtCode: string | null
  linkedOrgId: string | null
  sourceKey: string | null
  sourceRow: number | null
  sourceSheet: string | null
  sourceCell: string | null
  version: number
  publicName?: string
  publicAddress?: string
  publicAddressAuthorized?: boolean
}

export interface DirectoryInput {
  name: string
  brand: string
  recordType: DirectoryItem['recordType']
  originalRegionText: string
  regionCodes: string[]
  provinceCode?: string
  cityCode?: string
  expectedVersion?: number
}

export interface DirectoryImportRow {
  sourceKey: string
  sourceRow: number
  sourceSheet: string
  sourceCell: string
  brand: string
  name: string
  recordType: DirectoryItem['recordType']
  originalRegionText: string
  regionCodes: string[]
  matchStatus: DirectoryItem['matchStatus']
  status: string
  message: string
  recordId: string | null
}

export interface DirectoryImportResult {
  total: number
  created: number
  updated: number
  skipped: number
  failed: number
  unresolved: number
  rows: DirectoryImportRow[]
}

const base = '/platform/org/franchise-directory'
export const getFranchiseDirectory = (params: {
  keyword?: string
  brandId?: string
  matchStatus?: string
  pageNo: number
  pageSize: number
}): Promise<{ list: DirectoryItem[]; total: number }> => request.get({ url: base, params })

export const getDirectoryCandidates = (keyword: string): Promise<DirectoryItem[]> =>
  request.get({ url: `${base}/candidates`, params: { keyword } })

export const getDirectoryRegions = (): Promise<MapRegion[]> =>
  request.get({ url: `${base}/regions` })

export const createDirectoryRecord = (data: DirectoryInput): Promise<DirectoryItem> =>
  request.post({ url: base, data })

export const updateDirectoryRecord = (id: string, data: DirectoryInput): Promise<DirectoryItem> =>
  request.put({ url: `${base}/${encodeURIComponent(id)}`, data })

export const updateDirectoryMapDisplay = (
  id: string,
  data: {
    publicName: string
    publicAddress: string
    publicAddressAuthorized: boolean
    expectedVersion: number
  }
): Promise<DirectoryItem> =>
  request.put({ url: `${base}/${encodeURIComponent(id)}/map-display`, data })

// request.post unwraps CommonResult.data; request.upload has a different response contract.
function uploadDirectory(file: File, precheck: boolean): Promise<DirectoryImportResult> {
  const data = new FormData()
  data.append('file', file)
  return request.post({
    url: `${base}/import${precheck ? '/precheck' : ''}`,
    data,
    headersType: 'multipart/form-data',
    timeout: 120000
  })
}

export const precheckDirectoryImport = (file: File) => uploadDirectory(file, true)
export const submitDirectoryImport = (file: File) => uploadDirectory(file, false)

export const onboardFromDirectory = (data: {
  recordId: string
  expectedVersion: number
  districtCode: string
  adminName: string
  adminMobile: string
  areaPinyin?: string
  ownerPinyin?: string
  remark?: string
}): Promise<FranchiseOnboardResult> =>
  request.post({ url: '/platform/org/franchise-onboard/from-directory', data })
