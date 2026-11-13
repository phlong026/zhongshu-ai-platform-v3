import request from '@/config/axios'

/** 当前岗位所属组织的钱包（GET /platform/wallet/account，权限 platform:wallet:read）。 */
export interface WalletAccount {
  walletId: string
  orgId: string
  status: string
  availablePoints: number
  reservedPoints: number
  version: number
}

/** 钱包流水（GET /platform/wallet/ledger，按创建时间倒序分页）。 */
export interface WalletLedgerItem {
  id: string
  type: string
  delta: number
  availableAfter: number
  reservedAfter: number
  bizType: string
  bizId: string
  actorId: string
  reason: string
  createTime: string
}

export interface WalletLedgerPage {
  list: WalletLedgerItem[]
  total: number
}

export const getWalletAccount = (): Promise<WalletAccount> =>
  request.get({ url: '/platform/wallet/account', silentError: true })

export const getWalletLedger = (params: {
  pageNo: number
  pageSize: number
}): Promise<WalletLedgerPage> =>
  request.get({ url: '/platform/wallet/ledger', params, silentError: true })
