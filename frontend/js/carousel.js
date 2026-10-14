/**
 * @typedef CarouselItem 輪播圖輪播項
 * @type {Object}
 * @property {string} serial 系列
 * @property {string} title 標題
 * @property {string} desc 標題下的簡短描述
 * @property {string} thumbnail 輪播圖的圖片
 * @property {string | null} href 按下輪播圖後跳轉的地址
 */

/** @type {Array<CarouselItem>} */
// thumbnail 和 href 在下面的 for 迴圈 做賦值處理
let carouselList = [
  {
    serial: '01',
    title: 'Migration',
    desc: '#Seamless move to the cloud#',
    thumbnail: '../img/migration.svg',
    href: '../img/migration.svg',
  },
  {
    serial: '02',
    title: 'Continuity',
    desc: '#Always-on, zero downtime#',
    thumbnail: '../img/continuity.svg',
    href: '../img/continuity.svg',
  },
  {
    serial: '03',
    title: 'Integrity',
    desc: '#Trusted, consistent data#',
    thumbnail: '../img/integrity.svg',
    href: '../img/integrity.svg',
  },
  {
    serial: '04',
    title: 'Security',
    desc: '#Protected end to end#',
    thumbnail: '../img/security.svg',
    href: '../img/security.svg',
  },
];
// 初始化輪播圖基礎佈局
carouselList.forEach((item, index) => {
  document.querySelector('#media-list').innerHTML += `
  <div 
    class="media-list-item"
    data-index="${index}"
    data-serial="${item.serial}"
    data-title="${item.title}"
    data-desc="${item.desc}"
    data-thumbnail="${item.thumbnail}"
  >
    <div 
      class="media-list-item-img" 
      style="background-image: url(${item.thumbnail})"
      data-title="${item.title}"
    ></div>
  </div>
  `;
  document.querySelector('#media-layer-front .media-nav-wrapper').innerHTML += `
  <div
    class="media-nav-item"
    active="${index === 0}"
    data-index="${index}"
    data-serial="${item.serial}"
    data-title="${item.title}"
    data-desc="${item.desc}"
    data-thumbnail="${item.thumbnail}"
  ></div>
  `;
});

let activeIndex = 0; // 初始化激活的輪播索引
const layerFront = document.querySelector('#media-layer-front');
const mediaSerial = layerFront.querySelector('.media-info-serial');
const mediaTitle = layerFront.querySelector('.media-info-title');
const mediaDetail = layerFront.querySelector('.media-info-detail');
const mediaMainPic = document.querySelector('#media-layer-view .media-main-pic');
const mediaImage = mediaMainPic.querySelector('.media-img');
const mediaList = document.querySelector('#media-list');

// 初始化輪播資訊
mediaImage.href = carouselList[0].href;
mediaImage.style = `background-image: url(${carouselList[0].thumbnail}); transform-origin: left top; transform: scale(1)`;
mediaSerial.innerHTML = carouselList[0].serial;
mediaTitle.innerHTML = carouselList[0].title;
mediaDetail.innerHTML = carouselList[0].desc;

/**
 * 設定輪播項間隔、點擊監聽事件
 */
for (let i = 0; i < carouselList.length; i++) {
  let mediaItem = mediaList.querySelector(`.media-list-item:nth-child(${i + 1})`);
  let navItem = layerFront.querySelector(`.media-nav-item:nth-child(${i + 1})`);

  const mediaListItem = mediaList.querySelector(`.media-list-item:nth-child(1)`);
  const mediaListItemWidth = getComputedStyle(mediaListItem).width.replace('px', '');
  let mediaListItemPaddingRight = getComputedStyle(mediaListItem).paddingRight.replace('px', '');

  // 計算x位移之闊度 = media-list-item 的 width + 左右padding
  let xWidth = (parseFloat(mediaListItemWidth) + parseFloat(mediaListItemPaddingRight) * 2).toFixed(
    0,
  );

  // 顯示前邊四張圖片，其他隱藏
  if (i <= 3) {
    mediaItem.style.transform = `translateX(${xWidth * i}px)`;
    mediaItem.style.opacity = '1';
  } else {
    mediaItem.style.transform = `translateX(${xWidth * 3}px)`;
    mediaItem.style.opacity = '0';
    mediaItem.style.pointerEvents = 'none';
  }

  // 輪播列表 輪播指示標同時添加點擊處理器
  let array = [mediaItem, navItem];
  array.forEach((item) => {
    item.addEventListener('click', () => {
      if (parseInt(item.dataset.index) > parseInt(activeIndex)) {
        carouselItemSwitching(i, 'left');
      } else if (parseInt(item.dataset.index) < parseInt(activeIndex)) {
        carouselItemSwitching(i, 'right');
      }
      activeIndex = item.dataset.index;
    });
  });
}

/**
 * 左右箭咀click切換輪播事件
 */
const arrowBtnPrev = document.querySelector('#arrow-btn-prev');
const arrowBtnNext = document.querySelector('#arrow-btn-next');

arrowBtnPrev.addEventListener('click', () => {
  activeIndex > 0 ? activeIndex-- : (activeIndex = carouselList.length - 1);
  carouselItemSwitching(activeIndex, 'right');
});

arrowBtnNext.addEventListener('click', () => {
  activeIndex < carouselList.length - 1 ? activeIndex++ : (activeIndex = 0);
  carouselItemSwitching(activeIndex, 'left');
});

/**
 * 輪播項切換動畫
 * @param {number} index 激活的索引
 * @param {'left' | 'right'} direction 動畫方向 (1.'left', 2.'right')
 */
function carouselItemSwitching(index, direction) {
  // 清空舊的輪播指示標激活狀態
  for (let i = 0; i < carouselList.length; i++) {
    layerFront.querySelector(`.media-nav-item:nth-child(${i + 1})`).setAttribute('active', 'false');
  }
  // 激活當前選中的輪播指示標
  layerFront
    .querySelector(`.media-nav-item:nth-child(${index + 1})`)
    .setAttribute('active', 'true');

  imageZoom(0.25, direction, carouselList[index].thumbnail, carouselList[index].href).then(
    () => {},
  );
  slideInText(mediaSerial, direction, 0.2, 0.4, carouselList[index].serial).then(() => {});
  slideInText(mediaTitle, direction, 0.2, 0.5, carouselList[index].title).then(() => {});
  slideInText(mediaDetail, direction, 0.2, 0.6, carouselList[index].desc).then(() => {});
  setSlidePosition(index);
}

function sleep(time) {
  return new Promise((resolve) => setTimeout(resolve, time));
}

/**
 * 文字滑入動畫
 * @param {HTMLElement} element 要套用動畫的HTML元素
 * @param {'left' | 'right'} direction 方向 (1.'left', 2.'right')
 * @param {number} duration 持續時間
 * @param {number} delay 延遲時間
 * @param {string} newText 滑入過後顯示的文字
 */
async function slideInText(element, direction, duration, delay, newText) {
  let a;
  if (direction === 'left') {
    a = -50;
  } else if (direction === 'right') {
    a = 50;
  }
  element.style.transition = `${duration}s ease-out`;

  await sleep(delay * 1000);
  element.style.opacity = `0`;
  element.style.transform = `translateX(${a}%)`;
  await sleep(duration * 1000);
  element.style.transform = `translateX(${-a}%)`;
  element.style.opacity = `0`;
  await sleep(duration * 1000);
  element.innerHTML = newText;
  element.style.transform = `translateX(0)`;
  element.style.opacity = `1`;
  await sleep(delay * 1000);
}

/**
 * 圖片切換動畫
 * @param {number} duration 持續時間
 * @param {'left' | 'right'} direction 方向 (1.'left', 2.'right')
 * @param {string} newImg 切換後的圖片
 * @param {string} href 圖片跳轉連結
 */
async function imageZoom(duration, direction, newImg, href) {
  let oldImgTransformOrigin;
  let newImgTransformOrigin;
  if (direction === 'left') {
    oldImgTransformOrigin = 'left top';
    newImgTransformOrigin = 'right bottom';
  } else if (direction === 'right') {
    oldImgTransformOrigin = 'right bottom';
    newImgTransformOrigin = 'left top';
  }

  mediaMainPic.innerHTML += mediaMainPic.innerHTML;
  const mediaOldImg = mediaMainPic.querySelector('.media-img:nth-child(1)');
  const mediaNewImg = mediaMainPic.querySelector('.media-img:nth-child(2)');
  mediaNewImg.href = href;
  mediaNewImg.style.backgroundImage = `url(${newImg})`;

  mediaOldImg.style.transformOrigin = oldImgTransformOrigin;
  mediaOldImg.style.transform = 'scale(1)';
  mediaOldImg.style.transition = `${duration}s`;

  mediaNewImg.style.transformOrigin = newImgTransformOrigin;
  mediaNewImg.style.transform = 'scale(0)';
  mediaNewImg.style.transition = `${duration}s`;

  await sleep(duration * 1000);
  mediaOldImg.style.transform = 'scale(0)';
  mediaNewImg.style.transform = 'scale(1)';
  await sleep(duration * 1000);
  mediaMainPic.innerHTML = mediaNewImg.outerHTML;
}

// 只有四張圖片，縮圖列固定顯示，高亮當前項
function setSlidePosition(activeIndex) {
  for (let i = 0; i < carouselList.length; i++) {
    const img = mediaList.querySelector(
      `.media-list-item:nth-child(${i + 1}) .media-list-item-img`,
    );
    img.style.opacity = i === activeIndex ? '1' : '';
  }
}
setSlidePosition(0);
