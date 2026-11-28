'use strict';

function id(value) {
  if (value === null || value === undefined || value === '') return '';
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '';
  return String(value).trim();
}

function field(row, camel, snake) {
  if (!row) return undefined;
  return row[camel] !== undefined ? row[camel] : row[snake];
}

function cents(value) {
  const amount = Number(value);
  return Number.isSafeInteger(amount) && amount >= 0 ? amount : null;
}

function isProject(row) {
  return String(row && row.scope || '').startsWith('project:');
}

function isBook(row) {
  return String(row && row.scope || '').startsWith('book:');
}

function rowProjectId(row) {
  const direct = id(field(row, 'projectId', 'project_id'));
  return direct || (isProject(row) ? id(String(row.scope).slice(8)) : '');
}

function candidates(input) {
  input = input || {};
  const stageId = id(input.stageId);
  const stages = Array.isArray(input.stages) ? input.stages : [];
  const receipts = Array.isArray(input.receipts) ? input.receipts : [];
  const refunds = Array.isArray(input.refunds) ? input.refunds : [];
  const books = Array.isArray(input.books) ? input.books : [];
  const stage = stages.find(row => id(field(row, 'id', 'id')) === stageId);
  if (!stage) return { items: [], hint: '合同期次信息已变化，请刷新后重试', blocked: true };

  const contractId = id(field(stage, 'contractId', 'contract_id'));
  const customerId = id(field(stage, 'customerId', 'customer_id'));
  if (!contractId || !customerId) return { items: [], hint: '合同客户信息不完整，暂不能抵扣，请先核对合同', blocked: true };

  const targetProjects = books.filter(row => isProject(row) &&
    id(field(row, 'saleContractId', 'sale_contract_id')) === contractId);
  if (targetProjects.length > 1) {
    return { items: [], hint: '该合同关联多个工程，暂不能抵扣，请先核对工程归属', blocked: true };
  }
  const targetProject = targetProjects[0] || null;
  const targetProjectId = targetProject ? rowProjectId(targetProject) : '';
  if (targetProject && (!targetProjectId ||
      (id(field(targetProject, 'customerId', 'customer_id')) &&
       id(field(targetProject, 'customerId', 'customer_id')) !== customerId))) {
    return { items: [], hint: '合同与工程的客户归属不一致，暂不能抵扣', blocked: true };
  }

  const total = cents(field(stage, 'amountCents', 'amount_cents'));
  const collected = cents(field(stage, 'collectedCents', 'collected_cents'));
  if (total === null || collected === null) {
    return { items: [], hint: '合同期次金额暂不可核对，请刷新后重试', blocked: true };
  }
  const remaining = Math.max(0, total - collected);
  if (remaining <= 0) return { items: [], hint: '该期款项已收齐，无需抵扣', blocked: false, remainingCents: 0 };

  const refundedByReceipt = new Map();
  const invalidRefundIds = new Set();
  let unsafeRefundIdentity = false;
  refunds.forEach(refund => {
    if (field(refund, 'status', 'status') !== '已核实') return;
    const rawReceiptId = field(refund, 'receiptId', 'receipt_id');
    const receiptId = id(rawReceiptId);
    if (!receiptId) {
      if (rawReceiptId !== null && rawReceiptId !== undefined && rawReceiptId !== '') unsafeRefundIdentity = true;
      return;
    }
    const amount = cents(field(refund, 'amountCents', 'amount_cents'));
    if (amount === null) {
      invalidRefundIds.add(receiptId);
      return;
    }
    refundedByReceipt.set(receiptId, (refundedByReceipt.get(receiptId) || 0) + amount);
  });
  if (unsafeRefundIdentity) {
    return { items: [], hint: '退款原单标识无法安全识别，暂不能抵扣，请刷新数据后重试', blocked: true };
  }

  const booksById = new Map();
  books.forEach(row => {
    const bookId = id(field(row, 'bookId', 'book_id'));
    if (bookId) booksById.set(bookId, row);
  });

  // A preliminary book that already supplied another contract is not a source for this contract.
  // Match the stored allocation reference; an unallocated receipt cannot carry that reference.
  const booksUsedByAnotherContract = new Set();
  receipts.forEach(receipt => {
    const bookId = id(field(receipt, 'bookId', 'book_id'));
    const sourceRef = String(field(receipt, 'sourceRef', 'source_ref') || '');
    const allocation = /^sale-allocation:([0-9]+):[0-9]+:[0-9]+$/.exec(sourceRef);
    if (bookId && allocation && allocation[1] !== contractId) booksUsedByAnotherContract.add(bookId);
  });

  const items = [];
  receipts.forEach(receipt => {
    const receiptId = id(field(receipt, 'id', 'id'));
    if (!receiptId || field(receipt, 'status', 'status') !== '已登记' ||
        field(receipt, 'sourceType', 'source_type') !== 'manual' ||
        String(field(receipt, 'sourceRef', 'source_ref') || '').trim()) return;
    if (invalidRefundIds.has(receiptId)) return;

    const gross = cents(field(receipt, 'amountCents', 'amount_cents'));
    if (gross === null || gross <= 0) return;
    const refunded = refundedByReceipt.get(receiptId) || 0;
    const available = gross - refunded;
    if (available <= 0 || available > remaining) return;

    const rawCustomerId = field(receipt, 'customerId', 'customer_id');
    const rawProjectId = field(receipt, 'projectId', 'project_id');
    const rawBookId = field(receipt, 'bookId', 'book_id');
    const receiptCustomerId = id(rawCustomerId);
    const receiptProjectId = id(rawProjectId);
    const bookId = id(rawBookId);
    if ((rawCustomerId != null && rawCustomerId !== '' && !receiptCustomerId) ||
        (rawProjectId != null && rawProjectId !== '' && !receiptProjectId) ||
        (rawBookId != null && rawBookId !== '' && !bookId)) return;
    if (receiptCustomerId && receiptCustomerId !== customerId) return;
    if (receiptProjectId && (!targetProjectId || receiptProjectId !== targetProjectId)) return;

    let sourceBook = null;
    if (bookId) {
      if (booksUsedByAnotherContract.has(bookId)) return;
      sourceBook = booksById.get(bookId);
      if (!sourceBook || (!isProject(sourceBook) && !isBook(sourceBook))) return;
      const bookCustomerId = id(field(sourceBook, 'customerId', 'customer_id'));
      const bookProjectId = rowProjectId(sourceBook);
      if (bookCustomerId && bookCustomerId !== customerId) return;
      if (bookProjectId && (!targetProjectId || bookProjectId !== targetProjectId)) return;
      if (isProject(sourceBook) && (!targetProjectId || bookProjectId !== targetProjectId)) return;
      if (isBook(sourceBook) && !bookCustomerId && !receiptCustomerId) return;

      const commissionId = id(field(sourceBook, 'commissionId', 'commission_id'));
      if (commissionId) {
        if (targetProject) {
          if (id(field(targetProject, 'commissionId', 'commission_id')) !== commissionId) return;
        } else {
          const commissionUsedByAnotherProject = books.some(row => isProject(row) &&
            id(field(row, 'commissionId', 'commission_id')) === commissionId &&
            id(field(row, 'saleContractId', 'sale_contract_id')) !== contractId);
          if (commissionUsedByAnotherProject) return;
        }
      }
    }

    let originLabel = '';
    if (sourceBook) {
      originLabel = isProject(sourceBook)
        ? (sourceBook.name || '原工程')
        : ('前期账本 · ' + (sourceBook.name || '未命名账本'));
    } else if (receiptProjectId && targetProject) {
      originLabel = targetProject.name || '原工程';
    } else if (receiptCustomerId) {
      originLabel = (stage.customer_name || stage.customerName || '同一客户') + ' · 未关联工程';
    } else {
      originLabel = '未关联客户/工程（请核对凭证）';
    }

    const receivedDate = String(field(receipt, 'receivedDate', 'received_date') || '').slice(0, 10);
    items.push({
      ...receipt,
      id: receiptId,
      stageId,
      availableAmountCents: available,
      refundedAmountCents: refunded,
      originLabel,
      receivedDate
    });
  });

  items.sort((a, b) => String(b.receivedDate || '').localeCompare(String(a.receivedDate || '')) ||
    String(b.id).localeCompare(String(a.id)));
  return {
    items,
    hint: items.length ? '' : '没有符合当前合同归属的未分配手工收款，请核对来源客户、工程及原始账本。',
    blocked: false,
    remainingCents: remaining
  };
}

module.exports = { candidates, id };
