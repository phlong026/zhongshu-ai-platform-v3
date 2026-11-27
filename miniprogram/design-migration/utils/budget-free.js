// Check the backend before sending any budget request, including an old interrupted attempt.
module.exports = async function assertFreeBudget(api) {
  const quote = await api.getBudgetPointQuote();
  const price = quote && quote.companyPrice;
  if (!price || price.subject !== 'budget_estimate' || (price.quantity !== 1 && price.quantity !== '1')
      || (price.credits !== 0 && price.credits !== '0') || price.priceVersion !== 'budget-free-v1') {
    throw Error('当前服务尚未切换为免费测算，未提交预算，请稍后重试');
  }
};
