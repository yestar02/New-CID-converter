// src/main/resources/static/main.js
if (typeof DisableDevtool!=='undefined') DisableDevtool({disableMenu:true,clearLog:true});
const $=s=>document.querySelector(s),copy=t=>navigator.clipboard.writeText(t);
$('#frm').addEventListener('submit',async e=>{
  e.preventDefault();
  const url=$('#agodaUrl').value.trim();
  if(!url)return alert('URL을 입력하세요.');
  $('#loading').style.display='block';
  ['#hotelTitle','#tbl','#aff'].forEach(sel=>document.querySelector(sel).style.display='none');
  $('#tbl tbody').innerHTML='';$('#cheapest').innerHTML='';$('#affList').innerHTML='';
  try{
    const res=await fetch('/api/convert',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url})})
                      .then(r=>r.json());
    if(!res.success){alert(res.message);return;}
    $('#hotelTitle').textContent=`${res.hotel} – 가격 비교`;
    $('#hotelTitle').style.display='block';
    res.priced.forEach(item=>{
      const tr=document.createElement('tr');
      const priceDisplay=item.isSoldOut?'매진':item.price.toLocaleString();
      const priceClass=item.isSoldOut?'sold-out':'';
      tr.innerHTML=`
        <td>${item.label}</td>
        <td class="${priceClass}">${priceDisplay}</td>
        <td><a href="${item.url}" target="_blank">열기</a></td>
        <td><button data-url="${item.url}">복사</button></td>`;
      $('#tbl tbody').appendChild(tr);
    });
    const avail=res.priced.filter(i=>!i.isSoldOut&&i.price>0);
    if(avail.length){
      const c=avail[0];
      $('#cheapest').innerHTML=`
        <td>🏆 최저가</td>
        <td>${c.price.toLocaleString()}</td>
        <td><a href="${c.url}" target="_blank">열기</a></td>
        <td><button data-url="${c.url}">복사</button></td>`;
    } else {
      $('#cheapest').innerHTML=`<td colspan="4" class="no-available">모든 객실이 매진입니다.</td>`;
    }
    $('#tbl').style.display='table';
    res.affiliateLinks.forEach(link=>{
      const li=document.createElement('li');
      li.innerHTML=`<a href="${link.url}" target="_blank">${link.label}</a>`;
      $('#affList').appendChild(li);
    });
    $('#aff').style.display='block';
    document.querySelectorAll('button[data-url]').forEach(b=>b.addEventListener('click',()=>copy(b.dataset.url)));
  }catch(e){console.error(e);alert('오류 발생');}
  finally {$('#loading').style.display='none';}
});
