const navigation = require('../../utils/navigation');
const getDesignApp = require('../../utils/context').getApp;
'use strict';
const { protectedPage } = require('../../utils/access');
const { budgetPage } = require('../../utils/budget-view');
protectedPage(budgetPage('EXTERIOR'));
