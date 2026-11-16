const navigation = require('../../utils/navigation');
const getDesignApp = require('../../utils/context').getApp;
'use strict';
const { protectedPage } = require('../../utils/access');
const { categoryPage } = require('../../utils/budget-selection');
protectedPage(categoryPage('BODY'));
