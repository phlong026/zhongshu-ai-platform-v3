function parameters(detail) {
  const result = {};
  ['faceWidth','depth','floorCount','buildingArea','footprintArea','roofArea'].forEach(key => {
    const value = detail[key];
    if (value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)) && Number(value)>0)
      result[key] = key === 'floorCount' ? Number(value) : String(value);
  });
  if(detail.regionCode)result.regionCode=String(detail.regionCode);
  return result;
}
module.exports = {parameters};
