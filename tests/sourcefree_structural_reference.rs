// PUBLIC SOURCE-FREE reference only. No Oreslang compiler source, Git objects, or credentials.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Kind { Record, Struct, Class, Entry, Actor, Scalar, Tuple, FixedArray }
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Ty { Int, Bool, Text }
#[derive(Clone, Copy)]
struct Field { name: &'static str, ty: Ty, public: bool }
struct Carrier { kind: Kind, fields: Vec<Field> }
fn project(value: &Carrier, want: &[Field], explicit: bool, readonly: bool) -> bool {
    if !explicit || !readonly ||
       !matches!(value.kind, Kind::Record | Kind::Struct | Kind::Class | Kind::Entry) { return false; }
    want.iter().all(|need| {
        (value.kind != Kind::Entry || matches!(need.name,"key"|"value")) &&
        value.fields.iter().any(|actual| actual.public && actual.name == need.name && actual.ty == need.ty)
    })
}
fn positional(src: &[Ty], want: &[Ty], kind: Kind) -> bool {
    kind == Kind::Tuple && src.len() == want.len() && src.iter().zip(want).all(|(a,b)| a==b)
}
fn method_alpha(src_generics: &[&str], dst_generics: &[&str],
                src_sig: &[&str], dst_sig: &[&str]) -> bool {
    if src_generics.len()!=dst_generics.len() || src_sig.len()!=dst_sig.len() {return false;}
    src_sig.iter().zip(dst_sig.iter()).all(|(s,d)| {
        match (src_generics.iter().position(|x| x==s),dst_generics.iter().position(|x| x==d)) {
          (Some(i),Some(j))=>i==j,(None,None)=>s==d,_=>false
        }
    })
}
#[test]
fn structural_shape_security() {
    let record=Carrier { kind:Kind::Record, fields:vec![
        Field{name:"id",ty:Ty::Int,public:true},
        Field{name:"secret",ty:Ty::Text,public:false}
    ]};
    assert!(project(&record,&[Field{name:"id",ty:Ty::Int,public:true}],true,true));
    assert!(!project(&record,&[Field{name:"secret",ty:Ty::Text,public:true}],true,true));
    assert!(!project(&record,&[Field{name:"id",ty:Ty::Bool,public:true}],true,true));
    assert!(!project(&record,&[],true,false));
    assert!(!project(&record,&[],false,true));
    for kind in [Kind::Actor,Kind::Scalar,Kind::Tuple,Kind::FixedArray] {
       assert!(!project(&Carrier {kind,fields:vec![]},&[],true,true));
    }
    for kind in [Kind::Struct,Kind::Class] {
       assert!(project(&Carrier {kind,fields:vec![]},&[],true,true));
    }
    let entry=Carrier {kind:Kind::Entry,fields:vec![
       Field{name:"key",ty:Ty::Int,public:true},
       Field{name:"value",ty:Ty::Text,public:true},
       Field{name:"hidden",ty:Ty::Bool,public:true},
    ]};
    assert!(project(&entry,&[Field{name:"value",ty:Ty::Text,public:true}],true,true));
    assert!(!project(&entry,&[Field{name:"hidden",ty:Ty::Bool,public:true}],true,true));
}
#[test]
fn strict_tuple_and_method_binders() {
    assert!(positional(&[Ty::Int,Ty::Text],&[Ty::Int,Ty::Text],Kind::Tuple));
    assert!(!positional(&[Ty::Int,Ty::Text],&[Ty::Int],Kind::Tuple));
    assert!(!positional(&[Ty::Int,Ty::Text],&[Ty::Bool,Ty::Text],Kind::Tuple));
    assert!(!positional(&[Ty::Int],&[Ty::Int],Kind::FixedArray));
    assert!(method_alpha(&["T"],&["U"],&["T","int"],&["U","int"]));
    assert!(!method_alpha(&["T"],&["U"],&["T","T"],&["U","int"]));
    assert!(!method_alpha(&["T","S"],&["U"],&["T"],&["U"]));
    assert!(!method_alpha(&["T"],&["U"],&["T","Option<T>"],&["U","Option<U>"]));
}
#[test]
fn randomized_width_and_type_preservation_25000() {
    let names:&[&str]=&["a","b","c","d","e","f","g","h"];
    let mut seed:u32=0x5eed1234;
    for _ in 0..25_000 {
        seed=seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
        let count=(seed%8+1) as usize;
        let mut fields=Vec::new();
        for &name in names.iter().take(count) {
            seed=seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            fields.push(Field {name,ty:if seed&1==0{Ty::Int}else{Ty::Text},public:true});
        }
        seed=seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
        let sample=fields[(seed as usize)%count];
        let record=Carrier {kind:Kind::Record,fields};
        assert!(project(&record,&[sample],true,true));
        let changed=Field{ty:if sample.ty==Ty::Int{Ty::Text}else{Ty::Int},..sample};
        assert!(!project(&record,&[changed],true,true));
    }
}
